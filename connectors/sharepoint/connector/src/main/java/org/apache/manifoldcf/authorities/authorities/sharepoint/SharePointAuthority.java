/* $Id$ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.authorities.authorities.sharepoint;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.authorities.interfaces.*;
import org.apache.manifoldcf.authorities.system.Logging;
import org.apache.manifoldcf.authorities.system.ManifoldCF;

import java.io.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.TimeUnit;
import javax.naming.*;
import javax.naming.ldap.*;
import javax.naming.directory.*;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.util.EntityUtils;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;


/** This is the Active Directory implementation of the IAuthorityConnector interface.
* Access tokens for this connector are simple SIDs, except for the "global deny" token, which
* is designed to allow the authority to shut off access to all authorized documents when the
* user is unrecognized or the domain controller does not respond.
*/
public class SharePointAuthority extends org.apache.manifoldcf.authorities.authorities.BaseAuthorityConnector
{
  public static final String _rcsid = "@(#)$Id$";

  // Data from the parameters
  
  /** The list of suffixes and the associated domain controllers */
  private List<DCRule> dCRules = null;
  /** How to create a connection for a DC, keyed by DC name */
  private Map<String,DCConnectionParameters> dCConnectionParameters = null;
  
  private boolean hasSessionParameters = false;
  private String cacheLifetime = null;
  private String cacheLRUsize = null;
  private long responseLifetime = 60000L;
  private int LRUsize = 1000;

  /** Session information for all DC's we talk with. */
  private Map<String,DCSessionInfo> sessionInfo = null;
  
  /** Cache manager. */
  private ICacheManager cacheManager = null;
  
  /** The length of time in milliseconds that an connection remains idle before expiring.  Currently 5 minutes. */
  private static final long ADExpirationInterval = 300000L;
  
  /** Length of time that a SharePoint session can remain idle */
  private static final long SharePointExpirationInterval = 300000L;
  
  // SharePoint server parameters
  
  private String serverProtocol = null;
  private String serverUrl = null;
  private String fileBaseUrl = null;
  private String userName = null;
  private String strippedUserName = null;
  private String password = null;
  private String ntlmDomain = null;
  private String serverName = null;
  private String serverLocation = null;
  private String encodedServerLocation = null;
  private int serverPort = -1;

  private SPSProxyHelper proxy = null;

  private boolean isClaimSpace = false;
  
  private long sharepointSessionTimeout;
  
  // SSL support
  private String keystoreData = null;
  private IKeystoreManager keystoreManager = null;
  
  private ClientConnectionManager connectionManager = null;
  private HttpClient httpClient = null;

  // Current host name
  private static String currentHost = null;
  static
  {
    // Find the current host name
    try
    {
      java.net.InetAddress addr = java.net.InetAddress.getLocalHost();

      // Get hostname
      currentHost = addr.getHostName();
    }
    catch (UnknownHostException e)
    {
    }
  }

  /** Constructor.
  */
  public SharePointAuthority()
  {
  }

  /** Set thread context.
  */
  @Override
  public void setThreadContext(IThreadContext tc)
    throws ManifoldCFException
  {
    super.setThreadContext(tc);
    cacheManager = CacheManagerFactory.make(tc);
  }
  
  /** Clear thread context.
  */
  @Override
  public void clearThreadContext()
  {
    super.clearThreadContext();
    cacheManager = null;
  }
  
  /** Connect.  The configuration parameters are included.
  *@param configParams are the configuration parameters for this connection.
  */
  @Override
  public void connect(ConfigParams configParams)
  {
    super.connect(configParams);
    
    // Allocate the session data, currently empty
    sessionInfo = new HashMap<String,DCSessionInfo>();
    
    // Set up the DC param set, and the rules
    dCRules = new ArrayList<DCRule>();
    dCConnectionParameters = new HashMap<String,DCConnectionParameters>();
    // Read DC info from the config parameters
    for (int i = 0; i < params.getChildCount(); i++)
    {
      ConfigNode cn = params.getChild(i);
      if (cn.getType().equals(SharePointConfig.NODE_DOMAINCONTROLLER))
      {
        // Domain controller name is the actual key...
        String dcName = cn.getAttributeValue(SharePointConfig.ATTR_DOMAINCONTROLLER);
        // Set up the parameters for the domain controller
        dCConnectionParameters.put(dcName,new DCConnectionParameters(cn.getAttributeValue(SharePointConfig.ATTR_USERNAME),
          deobfuscate(cn.getAttributeValue(SharePointConfig.ATTR_PASSWORD)),
          cn.getAttributeValue(SharePointConfig.ATTR_AUTHENTICATION),
          cn.getAttributeValue(SharePointConfig.ATTR_USERACLsUSERNAME)));
        // Order-based rule, as well
        dCRules.add(new DCRule(cn.getAttributeValue(SharePointConfig.ATTR_SUFFIX),dcName));
      }
    }
    
    cacheLifetime = params.getParameter(SharePointConfig.PARAM_CACHELIFETIME);
    if (cacheLifetime == null)
      cacheLifetime = "1";
    cacheLRUsize = params.getParameter(SharePointConfig.PARAM_CACHELRUSIZE);
    if (cacheLRUsize == null)
      cacheLRUsize = "1000";    
  }

  protected static String deobfuscate(String input)
  {
    if (input == null)
      return null;
    try
    {
      return ManifoldCF.deobfuscate(input);
    }
    catch (ManifoldCFException e)
    {
      return "";
    }
  }
  
  // All methods below this line will ONLY be called if a connect() call succeeded
  // on this instance!

  /** Check connection for sanity.
  */
  @Override
  public String check()
    throws ManifoldCFException
  {
    // Set up the basic AD session...
    getSessionParameters();
    // Clear the DC session info, so we're forced to redo it
    for (Map.Entry<String,DCSessionInfo> sessionEntry : sessionInfo.entrySet())
    {
      sessionEntry.getValue().closeConnection();
    }
    // Loop through all domain controllers and attempt to establish a session with each one.
    for (String domainController : dCConnectionParameters.keySet())
    {
      createDCSession(domainController);
    }
    
    // SharePoint check
    getSharePointSession();
    try
    {
      URL urlServer = new URL( serverUrl );
    }
    catch ( MalformedURLException e )
    {
      return "Illegal SharePoint url: "+e.getMessage();
    }

    try
    {
      proxy.checkConnection( "/" );
    }
    catch (ManifoldCFException e)
    {
      return e.getMessage();
    }

    return super.check();
  }

  /** Create or lookup a session for a domain controller.
  */
  protected LdapContext createDCSession(String domainController)
    throws ManifoldCFException
  {
    getSessionParameters();
    DCConnectionParameters parms = dCConnectionParameters.get(domainController);
    // Find the session in the hash, if it exists
    DCSessionInfo session = sessionInfo.get(domainController);
    if (session == null)
    {
      session = new DCSessionInfo();
      sessionInfo.put(domainController,session);
    }
    return session.getADSession(domainController,parms);
  }
  
  /** Poll.  The connection should be closed if it has been idle for too long.
  */
  @Override
  public void poll()
    throws ManifoldCFException
  {
    long currentTime = System.currentTimeMillis();
    for (Map.Entry<String,DCSessionInfo> sessionEntry : sessionInfo.entrySet())
    {
      sessionEntry.getValue().closeIfExpired(currentTime);
    }
    if (proxy != null && System.currentTimeMillis() >= sharepointSessionTimeout)
      expireSharePointSession();
    if (connectionManager != null)
      connectionManager.closeIdleConnections(60000L,TimeUnit.MILLISECONDS);
    super.poll();
  }
  
  /** Close the connection.  Call this before discarding the repository connector.
  */
  @Override
  public void disconnect()
    throws ManifoldCFException
  {
    // Clean up caching parameters
    
    cacheLifetime = null;
    cacheLRUsize = null;
    
    // Clean up AD parameters
    
    hasSessionParameters = false;

    // Close all connections
    for (Map.Entry<String,DCSessionInfo> sessionEntry : sessionInfo.entrySet())
    {
      sessionEntry.getValue().closeConnection();
    }
    sessionInfo = null;
    
    // Clean up SharePoint parameters
    
    serverUrl = null;
    fileBaseUrl = null;
    userName = null;
    strippedUserName = null;
    password = null;
    ntlmDomain = null;
    serverName = null;
    serverLocation = null;
    encodedServerLocation = null;
    serverPort = -1;

    keystoreData = null;
    keystoreManager = null;

    proxy = null;
    httpClient = null;
    if (connectionManager != null)
      connectionManager.shutdown();
    connectionManager = null;

    super.disconnect();
  }

  /** Obtain the access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the response tokens (according to the current authority).
  * (Should throws an exception only when a condition cannot be properly described within the authorization response object.)
  */
  @Override
  public AuthorizationResponse getAuthorizationResponse(String userName)
    throws ManifoldCFException
  {
    // This sets up parameters we need to construct the response description
    getSessionParameters();

    // Construct a cache description object
    ICacheDescription objectDescription = new AuthorizationResponseDescription(userName,
      dCConnectionParameters,dCRules,this.responseLifetime,this.LRUsize);
    
    // Enter the cache
    ICacheHandle ch = cacheManager.enterCache(new ICacheDescription[]{objectDescription},null,null);
    try
    {
      ICacheCreateHandle createHandle = cacheManager.enterCreateSection(ch);
      try
      {
        // Lookup the object
        AuthorizationResponse response = (AuthorizationResponse)cacheManager.lookupObject(createHandle,objectDescription);
        if (response != null)
          return response;
        // Create the object.
        response = getAuthorizationResponseUncached(userName);
        // Save it in the cache
        cacheManager.saveObject(createHandle,objectDescription,response);
        // And return it...
        return response;
      }
      finally
      {
        cacheManager.leaveCreateSection(createHandle);
      }
    }
    finally
    {
      cacheManager.leaveCache(ch);
    }
  }
  
  /** Obtain the access tokens for a given user name, uncached.
  *@param userName is the user name or identifier.
  *@return the response tokens (according to the current authority).
  * (Should throws an exception only when a condition cannot be properly described within the authorization response object.)
  */
  protected AuthorizationResponse getAuthorizationResponseUncached(String userName)
    throws ManifoldCFException
  {
    //String searchBase = "CN=Administrator,CN=Users,DC=qa-ad-76,DC=metacarta,DC=com";
    int index = userName.indexOf("@");
    if (index == -1)
      throw new ManifoldCFException("Username is in unexpected form (no @): '"+userName+"'");

    String userPart = userName.substring(0,index);
    String domainPart = userName.substring(index+1);

    List<String> theGroups = new ArrayList<String>();
    
    // First, look up user in SharePoint.
    getSharePointSession();
    List<String> sharePointTokens = proxy.getAccessTokens("/", domainPart + "\\" + userPart);
    if (sharePointTokens == null)
      return RESPONSE_USERNOTFOUND;
    theGroups.addAll(sharePointTokens);
    
    // Use AD only if Claim Space
    if (isClaimSpace)
    {
      try
      {
        List<String> adTokens = getADTokens(userPart,domainPart);
        // User not present in AD is perfectly OK provided the user exists in SharePoint
        if (adTokens != null)
          theGroups.addAll(adTokens);
      }
      catch (NameNotFoundException e)
      {
        // This means that the user doesn't exist
        return RESPONSE_USERNOTFOUND;
      }
      catch (NamingException e)
      {
        // Unreachable
        return RESPONSE_UNREACHABLE;
      }
    }
    
    return new AuthorizationResponse(theGroups.toArray(new String[0]),AuthorizationResponse.RESPONSE_OK);
  }

  /** Obtain the default access tokens for a given user name.
  *@param userName is the user name or identifier.
  *@return the default response tokens, presuming that the connect method fails.
  */
  @Override
  public AuthorizationResponse getDefaultAuthorizationResponse(String userName)
  {
    // The default response if the getConnection method fails
    return RESPONSE_UNREACHABLE;
  }

  /** Get the AD-derived access tokens for a user and domain */
  protected List<String> getADTokens(String userPart, String domainPart)
    throws NameNotFoundException, NamingException, ManifoldCFException
  {
    // Now, look through the rules for the matching domain controller
    String domainController = null;
    for (DCRule rule : dCRules)
    {
      String suffix = rule.getSuffix();
      if (suffix.length() == 0 || domainPart.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT)) &&
        (suffix.length() == domainPart.length() || domainPart.charAt((domainPart.length()-suffix.length())-1) == '.'))
      {
        domainController = rule.getDomainControllerName();
        break;
      }
    }
    
    if (domainController == null)
      // No AD user
      return null;
    
    // Look up connection parameters
    DCConnectionParameters dcParams = dCConnectionParameters.get(domainController);
    if (dcParams == null)
      // No AD user
      return null;
        
    // Use the complete fqn if the field is the "userPrincipalName"
    String userACLsUsername = dcParams.getUserACLsUsername();
    if (userACLsUsername != null && userACLsUsername.equals("userPrincipalName")){
      userPart = userName;
    }
        
    //Build the DN searchBase from domain part
    StringBuilder domainsb = new StringBuilder();
    int j = 0;
    while (true)
    {
      if (j > 0)
        domainsb.append(",");

      int k = domainPart.indexOf(".",j);
      if (k == -1)
      {
        domainsb.append("DC=").append(ldapEscape(domainPart.substring(j)));
        break;
      }
      domainsb.append("DC=").append(ldapEscape(domainPart.substring(j,k)));
      j = k+1;
    }

    // Establish a session with the selected domain controller
    LdapContext ctx = createDCSession(domainController);  
        
    //Get DistinguishedName (for this method we are using DomainPart as a searchBase ie: DC=qa-ad-76,DC=metacarta,DC=com")
    String searchBase = getDistinguishedName(ctx, userPart, domainsb.toString(), userACLsUsername);
    if (searchBase == null)
      return null;

    //specify the LDAP search filter
    String searchFilter = "(objectClass=user)";

    //Create the search controls for finding the access tokens	
    SearchControls searchCtls = new SearchControls();

    //Specify the search scope, must be base level search for tokenGroups
    searchCtls.setSearchScope(SearchControls.OBJECT_SCOPE);
       
    //Specify the attributes to return
    String returnedAtts[]={"tokenGroups","objectSid"};
    searchCtls.setReturningAttributes(returnedAtts);

    //Search for tokens.  Since every user *must* have a SID, the "no user" detection should be safe.
    NamingEnumeration answer = ctx.search(searchBase, searchFilter, searchCtls);

    List<String> theGroups = new ArrayList<String>();

    //Loop through the search results
    while (answer.hasMoreElements())
    {
      SearchResult sr = (SearchResult)answer.next();
     
      //the sr.GetName should be null, as it is relative to the base object
            
      Attributes attrs = sr.getAttributes();
      if (attrs != null)
      {
        try
        {
          for (NamingEnumeration ae = attrs.getAll();ae.hasMore();) 
          {
            Attribute attr = (Attribute)ae.next();
            for (NamingEnumeration e = attr.getAll();e.hasMore();)
            {
              theGroups.add(groupTokenFromSID(sid2String((byte[])e.next())));
            }
          }
        }	 
        catch (NamingException e)
        {
          throw new ManifoldCFException(e.getMessage(),e);
        }
                                    
      }
    }

    if (theGroups.size() == 0)
      return null;
    
    // User is in AD, so add the 'everyone' group
    theGroups.add(groupTokenFromSID("S-1-1-0"));
    return theGroups;
  }

  protected String groupTokenFromSID(String SID)
  {
    // MHL; called only if Claim Space enabled
    return SID;
  }
  
  // UI support methods.
  //
  // These support methods are involved in setting up authority connection configuration information. The configuration methods cannot assume that the
  // current authority object is connected.  That is why they receive a thread context argument.
    
  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to add the required tabs to the list, and to output any
  * javascript methods that might be needed by the configuration editing HTML.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out, Locale locale, ConfigParams parameters, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add(Messages.getString(locale,"SharePointAuthority.DomainController"));
    tabsArray.add(Messages.getString(locale,"SharePointAuthority.Server"));
    tabsArray.add(Messages.getString(locale,"SharePointAuthority.AuthorizationModel"));
    tabsArray.add(Messages.getString(locale,"SharePointAuthority.Cache"));
    Messages.outputResourceWithVelocity(out,locale,"editConfiguration.js",null);
  }
  
  /** Output the configuration body section.
  * This method is called in the body section of the authority connector's configuration page.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html>, <body>, and <form> tags.  The name of the
  * form is "editconnection".
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out, Locale locale, ConfigParams parameters, String tabName)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInDomainControllerTab(velocityContext,out,parameters);
    fillInCacheTab(velocityContext,out,parameters);
    fillInServerTab(velocityContext,out,parameters);
    fillInAuthorizationModelTab(velocityContext,out,parameters);
    Messages.outputResourceWithVelocity(out,locale,"editConfiguration_DomainController.html",velocityContext);
    Messages.outputResourceWithVelocity(out,locale,"editConfiguration_Cache.html",velocityContext);
    Messages.outputResourceWithVelocity(out,locale,"editConfiguration_Server.html",velocityContext);
    Messages.outputResourceWithVelocity(out,locale,"editConfiguration_AuthorizationModel.html",velocityContext);
  }

  protected static void fillInAuthorizationModelTab(Map<String,Object> velocityContext, IHTTPOutput out, ConfigParams parameters)
    throws ManifoldCFException
  {
    String authorizationModel = parameters.getParameter(SharePointConfig.PARAM_AUTHORIZATIONMODEL);
    if (authorizationModel == null)
      authorizationModel = "Classic";
    
    // Fill in context
    velocityContext.put("AUTHORIZATIONMODEL", authorizationModel);
  }

  protected static void fillInServerTab(Map<String,Object> velocityContext, IHTTPOutput out, ConfigParams parameters)
    throws ManifoldCFException
  {
    String serverVersion = parameters.getParameter(SharePointConfig.PARAM_SERVERVERSION);
    if (serverVersion == null)
      serverVersion = "2.0";

    String serverProtocol = parameters.getParameter(SharePointConfig.PARAM_SERVERPROTOCOL);
    if (serverProtocol == null)
      serverProtocol = "http";

    String serverName = parameters.getParameter(SharePointConfig.PARAM_SERVERNAME);
    if (serverName == null)
      serverName = "localhost";

    String serverPort = parameters.getParameter(SharePointConfig.PARAM_SERVERPORT);
    if (serverPort == null)
      serverPort = "";

    String serverLocation = parameters.getParameter(SharePointConfig.PARAM_SERVERLOCATION);
    if (serverLocation == null)
      serverLocation = "";
      
    String userName = parameters.getParameter(SharePointConfig.PARAM_SERVERUSERNAME);
    if (userName == null)
      userName = "";

    String password = parameters.getObfuscatedParameter(SharePointConfig.PARAM_SERVERPASSWORD);
    if (password == null)
      password = "";
    else
      password = out.mapPasswordToKey(password);

    String keystore = parameters.getParameter(SharePointConfig.PARAM_SERVERKEYSTORE);
    IKeystoreManager localKeystore;
    if (keystore == null)
      localKeystore = KeystoreManagerFactory.make("");
    else
      localKeystore = KeystoreManagerFactory.make("",keystore);

    List<Map<String,String>> certificates = new ArrayList<Map<String,String>>();
    
    String[] contents = localKeystore.getContents();
    for (String alias : contents)
    {
      String description = localKeystore.getDescription(alias);
      if (description.length() > 128)
        description = description.substring(0,125) + "...";
      Map<String,String> certificate = new HashMap<String,String>();
      certificate.put("ALIAS", alias);
      certificate.put("DESCRIPTION", description);
      certificates.add(certificate);
    }
    
    // Fill in context
    velocityContext.put("SERVERVERSION", serverVersion);
    velocityContext.put("SERVERPROTOCOL", serverProtocol);
    velocityContext.put("SERVERNAME", serverName);
    velocityContext.put("SERVERPORT", serverPort);
    velocityContext.put("SERVERLOCATION", serverLocation);
    velocityContext.put("USERNAME", userName);
    velocityContext.put("PASSWORD", password);
    if (keystore != null)
      velocityContext.put("KEYSTORE", keystore);
    velocityContext.put("CERTIFICATELIST", certificates);
    
  }

  protected static void fillInDomainControllerTab(Map<String,Object> velocityContext, IPasswordMapperActivity mapper, ConfigParams parameters)
  {
    List<Map<String,String>> domainControllers = new ArrayList<Map<String,String>>();
    
    // Go through nodes looking for DC nodes
    for (int i = 0; i < parameters.getChildCount(); i++)
    {
      ConfigNode cn = parameters.getChild(i);
      if (cn.getType().equals(SharePointConfig.NODE_DOMAINCONTROLLER))
      {
        // Grab the info
        String dcSuffix = cn.getAttributeValue(SharePointConfig.ATTR_SUFFIX);
        String dcDomainController = cn.getAttributeValue(SharePointConfig.ATTR_DOMAINCONTROLLER);
        String dcUserName = cn.getAttributeValue(SharePointConfig.ATTR_USERNAME);
        String dcPassword = deobfuscate(cn.getAttributeValue(SharePointConfig.ATTR_PASSWORD));
        String dcAuthentication = cn.getAttributeValue(SharePointConfig.ATTR_AUTHENTICATION);
        String dcUserACLsUsername = cn.getAttributeValue(SharePointConfig.ATTR_USERACLsUSERNAME);
        domainControllers.add(createDomainControllerMap(mapper,dcSuffix,dcDomainController,dcUserName,dcPassword,dcAuthentication,dcUserACLsUsername));
      }
    }
    velocityContext.put("DOMAINCONTROLLERS",domainControllers);
  }

  protected static Map<String,String> createDomainControllerMap(IPasswordMapperActivity mapper, String suffix, String domainControllerName,
    String userName, String password, String authentication, String userACLsUsername)
  {
    Map<String,String> defaultMap = new HashMap<String,String>();
    if (suffix != null)
      defaultMap.put("SUFFIX",suffix);
    if (domainControllerName != null)
      defaultMap.put("DOMAINCONTROLLER",domainControllerName);
    if (userName != null)
      defaultMap.put("USERNAME",userName);
    if (password != null)
      defaultMap.put("PASSWORD",mapper.mapPasswordToKey(password));
    if (authentication != null)
      defaultMap.put("AUTHENTICATION",authentication);
    if (userACLsUsername != null)
      defaultMap.put("USERACLsUSERNAME",userACLsUsername);
    return defaultMap;
  }
  
  protected static void fillInCacheTab(Map<String,Object> velocityContext, IPasswordMapperActivity mapper, ConfigParams parameters)
  {
    String cacheLifetime = parameters.getParameter(SharePointConfig.PARAM_CACHELIFETIME);
    if (cacheLifetime == null)
      cacheLifetime = "1";
    velocityContext.put("CACHELIFETIME",cacheLifetime);
    String cacheLRUsize = parameters.getParameter(SharePointConfig.PARAM_CACHELRUSIZE);
    if (cacheLRUsize == null)
      cacheLRUsize = "1000";
    velocityContext.put("CACHELRUSIZE",cacheLRUsize);
  }
  
  /** Process a configuration post.
  * This method is called at the start of the authority connector's configuration page, whenever there is a possibility that form data for a connection has been
  * posted.  Its purpose is to gather form information and modify the configuration parameters accordingly.
  * The name of the posted form is "editconnection".
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the connection (and cause a redirection to an error page).
  */
  @Override
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext, Locale locale, ConfigParams parameters)
    throws ManifoldCFException
  {
    String x = variableContext.getParameter("dcrecord_count");
    if (x != null)
    {
      // Delete old nodes
      int i = 0;
      while (i < parameters.getChildCount())
      {
        ConfigNode cn = parameters.getChild(i);
        if (cn.getType().equals(SharePointConfig.NODE_DOMAINCONTROLLER))
          parameters.removeChild(i);
        else
          i++;
      }
      // Scan form fields and apply operations
      int count = Integer.parseInt(x);
      i = 0;
      String op;
      
      Set<String> seenDomains = new HashSet<String>();
      
      while (i < count)
      {
        op = variableContext.getParameter("dcrecord_op_"+i);
        if (op != null && op.equals("Insert"))
        {
          // Insert a new record right here
          addDomainController(seenDomains,parameters,
            variableContext.getParameter("dcrecord_suffix"),
            variableContext.getParameter("dcrecord_domaincontrollername"),
            variableContext.getParameter("dcrecord_username"),
            variableContext.mapKeyToPassword(variableContext.getParameter("dcrecord_password")),
            variableContext.getParameter("dcrecord_authentication"),
            variableContext.getParameter("dcrecord_userACLsUsername"));
        }
        if (op == null || !op.equals("Delete"))
        {
          // Add this record back in
          addDomainController(seenDomains,parameters,
            variableContext.getParameter("dcrecord_suffix_"+i),
            variableContext.getParameter("dcrecord_domaincontrollername_"+i),
            variableContext.getParameter("dcrecord_username_"+i),
            variableContext.mapKeyToPassword(variableContext.getParameter("dcrecord_password_"+i)),
            variableContext.getParameter("dcrecord_authentication_"+i),
            variableContext.getParameter("dcrecord_userACLsUsername_"+i));
        }
        i++;
      }
      op = variableContext.getParameter("dcrecord_op");
      if (op != null && op.equals("Add"))
      {
        // Insert a new record right here
        addDomainController(seenDomains,parameters,
          variableContext.getParameter("dcrecord_suffix"),
          variableContext.getParameter("dcrecord_domaincontrollername"),
          variableContext.getParameter("dcrecord_username"),
          variableContext.getParameter("dcrecord_password"),
          variableContext.getParameter("dcrecord_authentication"),
          variableContext.getParameter("dcrecord_userACLsUsername"));
      }
    }

    // Cache parameters
    
    String cacheLifetime = variableContext.getParameter("cachelifetime");
    if (cacheLifetime != null)
      parameters.setParameter(SharePointConfig.PARAM_CACHELIFETIME,cacheLifetime);
    String cacheLRUsize = variableContext.getParameter("cachelrusize");
    if (cacheLRUsize != null)
      parameters.setParameter(SharePointConfig.PARAM_CACHELRUSIZE,cacheLRUsize);
    
    // SharePoint server parameters
    
    String serverVersion = variableContext.getParameter("serverVersion");
    if (serverVersion != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERVERSION,serverVersion);

    String serverProtocol = variableContext.getParameter("serverProtocol");
    if (serverProtocol != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERPROTOCOL,serverProtocol);

    String serverName = variableContext.getParameter("serverName");

    if (serverName != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERNAME,serverName);

    String serverPort = variableContext.getParameter("serverPort");
    if (serverPort != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERPORT,serverPort);

    String serverLocation = variableContext.getParameter("serverLocation");
    if (serverLocation != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERLOCATION,serverLocation);

    String userName = variableContext.getParameter("userName");
    if (userName != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERUSERNAME,userName);

    String password = variableContext.getParameter("password");
    if (password != null)
      parameters.setObfuscatedParameter(SharePointConfig.PARAM_SERVERPASSWORD,variableContext.mapKeyToPassword(password));

    String keystoreValue = variableContext.getParameter("keystoredata");
    if (keystoreValue != null)
      parameters.setParameter(SharePointConfig.PARAM_SERVERKEYSTORE,keystoreValue);

    String configOp = variableContext.getParameter("configop");
    if (configOp != null)
    {
      if (configOp.equals("Delete"))
      {
        String alias = variableContext.getParameter("shpkeystorealias");
        keystoreValue = parameters.getParameter(SharePointConfig.PARAM_SERVERKEYSTORE);
        IKeystoreManager mgr;
        if (keystoreValue != null)
          mgr = KeystoreManagerFactory.make("",keystoreValue);
        else
          mgr = KeystoreManagerFactory.make("");
        mgr.remove(alias);
        parameters.setParameter(SharePointConfig.PARAM_SERVERKEYSTORE,mgr.getString());
      }
      else if (configOp.equals("Add"))
      {
        String alias = IDFactory.make(threadContext);
        byte[] certificateValue = variableContext.getBinaryBytes("shpcertificate");
        keystoreValue = parameters.getParameter(SharePointConfig.PARAM_SERVERKEYSTORE);
        IKeystoreManager mgr;
        if (keystoreValue != null)
          mgr = KeystoreManagerFactory.make("",keystoreValue);
        else
          mgr = KeystoreManagerFactory.make("");
        java.io.InputStream is = new java.io.ByteArrayInputStream(certificateValue);
        String certError = null;
        try
        {
          mgr.importCertificate(alias,is);
        }
        catch (Throwable e)
        {
          certError = e.getMessage();
        }
        finally
        {
          try
          {
            is.close();
          }
          catch (IOException e)
          {
            // Don't report anything
          }
        }

        if (certError != null)
        {
          // Redirect to error page
          return "Illegal certificate: "+certError;
        }
        parameters.setParameter(SharePointConfig.PARAM_SERVERKEYSTORE,mgr.getString());
      }
    }
    
    // Authorization model
    String authorizationModel = variableContext.getParameter("authorizationModel");
    if (authorizationModel != null)
      parameters.setParameter(SharePointConfig.PARAM_AUTHORIZATIONMODEL,authorizationModel);

    return null;
  }
  
  protected static void addDomainController(Set<String> seenDomains, ConfigParams parameters,
    String suffix, String domainControllerName, String userName, String password, String authentication,
    String userACLsUsername)
    throws ManifoldCFException
  {
    if (!seenDomains.contains(domainControllerName))
    {
      ConfigNode cn = new ConfigNode(SharePointConfig.NODE_DOMAINCONTROLLER);
      cn.setAttribute(SharePointConfig.ATTR_SUFFIX,suffix);
      cn.setAttribute(SharePointConfig.ATTR_DOMAINCONTROLLER,domainControllerName);
      cn.setAttribute(SharePointConfig.ATTR_USERNAME,userName);
      cn.setAttribute(SharePointConfig.ATTR_PASSWORD,ManifoldCF.obfuscate(password));
      cn.setAttribute(SharePointConfig.ATTR_AUTHENTICATION,authentication);
      cn.setAttribute(SharePointConfig.ATTR_USERACLsUSERNAME,userACLsUsername);
      parameters.addChild(parameters.getChildCount(),cn);
      seenDomains.add(domainControllerName);
    }
  }
  
  /** View configuration.
  * This method is called in the body section of the authority connector's view configuration page.  Its purpose is to present the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out, Locale locale, ConfigParams parameters)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    fillInDomainControllerTab(velocityContext,out,parameters);
    fillInCacheTab(velocityContext,out,parameters);
    fillInServerTab(velocityContext,out,parameters);
    fillInAuthorizationModelTab(velocityContext,out,parameters);
    Messages.outputResourceWithVelocity(out,locale,"viewConfiguration.html",velocityContext);
  }

  // Protected methods

  /** Get parameters needed for caching.
  */
  protected void getSessionParameters()
    throws ManifoldCFException
  {
    if (!hasSessionParameters)
    {
      try
      {
        responseLifetime = Long.parseLong(this.cacheLifetime) * 60L * 1000L;
        LRUsize = Integer.parseInt(this.cacheLRUsize);
      }
      catch (NumberFormatException e)
      {
        throw new ManifoldCFException("Cache lifetime or Cache LRU size must be an integer: "+e.getMessage(),e);
      }
      hasSessionParameters = true;
    }
  }
  
  protected void getSharePointSession()
    throws ManifoldCFException
  {
    if (proxy == null)
    {
      String serverVersion = params.getParameter( SharePointConfig.PARAM_SERVERVERSION );
      if (serverVersion == null)
        serverVersion = "2.0";
      // Authority needs to do nothing with SharePoint version right now.
      
      String authorizationModel = params.getParameter( SharePointConfig.PARAM_AUTHORIZATIONMODEL );
      isClaimSpace = (authorizationModel != null &&authorizationModel.equals("ClaimSpace"));

      serverProtocol = params.getParameter( SharePointConfig.PARAM_SERVERPROTOCOL );
      if (serverProtocol == null)
        serverProtocol = "http";
      try
      {
        String serverPort = params.getParameter( SharePointConfig.PARAM_SERVERPORT );
        if (serverPort == null || serverPort.length() == 0)
        {
          if (serverProtocol.equals("https"))
            this.serverPort = 443;
          else
            this.serverPort = 80;
        }
        else
          this.serverPort = Integer.parseInt(serverPort);
      }
      catch (NumberFormatException e)
      {
        throw new ManifoldCFException(e.getMessage(),e);
      }
      serverLocation = params.getParameter(SharePointConfig.PARAM_SERVERLOCATION);
      if (serverLocation == null)
        serverLocation = "";
      if (serverLocation.endsWith("/"))
        serverLocation = serverLocation.substring(0,serverLocation.length()-1);
      if (serverLocation.length() > 0 && !serverLocation.startsWith("/"))
        serverLocation = "/" + serverLocation;
      encodedServerLocation = serverLocation;
      serverLocation = decodePath(serverLocation);

      userName = params.getParameter(SharePointConfig.PARAM_SERVERUSERNAME);
      password = params.getObfuscatedParameter(SharePointConfig.PARAM_SERVERPASSWORD);
      int index = userName.indexOf("\\");
      if (index != -1)
      {
        strippedUserName = userName.substring(index+1);
        ntlmDomain = userName.substring(0,index);
      }
      else
      {
        strippedUserName = null;
        ntlmDomain = null;
      }

      serverUrl = serverProtocol + "://" + serverName;
      if (serverProtocol.equals("https"))
      {
        if (serverPort != 443)
          serverUrl += ":" + Integer.toString(serverPort);
      }
      else
      {
        if (serverPort != 80)
          serverUrl += ":" + Integer.toString(serverPort);
      }

      // Set up ssl if indicated
      keystoreData = params.getParameter(SharePointConfig.PARAM_SERVERKEYSTORE);

      PoolingClientConnectionManager localConnectionManager = new PoolingClientConnectionManager();
      localConnectionManager.setMaxTotal(1);
      connectionManager = localConnectionManager;

      if (keystoreData != null)
      {
        keystoreManager = KeystoreManagerFactory.make("",keystoreData);
        SSLSocketFactory myFactory = new SSLSocketFactory(keystoreManager.getSecureSocketFactory(), new BrowserCompatHostnameVerifier());
        Scheme myHttpsProtocol = new Scheme("https", 443, myFactory);
        connectionManager.getSchemeRegistry().register(myHttpsProtocol);
      }

      fileBaseUrl = serverUrl + encodedServerLocation;

      BasicHttpParams params = new BasicHttpParams();
      params.setBooleanParameter(CoreConnectionPNames.TCP_NODELAY,true);
      params.setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK,false);
      params.setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT,60000);
      params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT,900000);
      params.setBooleanParameter(ClientPNames.ALLOW_CIRCULAR_REDIRECTS,true);
      DefaultHttpClient localHttpClient = new DefaultHttpClient(connectionManager,params);
      // No retries
      localHttpClient.setHttpRequestRetryHandler(new HttpRequestRetryHandler()
        {
          public boolean retryRequest(
            IOException exception,
            int executionCount,
            HttpContext context)
          {
            return false;
          }
       
        });
      localHttpClient.setRedirectStrategy(new DefaultRedirectStrategy());
      if (strippedUserName != null)
      {
        localHttpClient.getCredentialsProvider().setCredentials(
          new AuthScope(serverName,serverPort),
          new NTCredentials(strippedUserName, password, currentHost, ntlmDomain));
      }

      httpClient = localHttpClient;
      
      proxy = new SPSProxyHelper( serverUrl, encodedServerLocation, serverLocation, userName, password,
        org.apache.manifoldcf.sharepoint.CommonsHTTPSender.class, "sharepoint-client-config.wsdd",
        httpClient );
      
    }
    sharepointSessionTimeout = System.currentTimeMillis() + SharePointExpirationInterval;
  }
  
  protected void expireSharePointSession()
    throws ManifoldCFException
  {
    serverUrl = null;
    fileBaseUrl = null;
    userName = null;
    strippedUserName = null;
    password = null;
    ntlmDomain = null;
    serverLocation = null;
    encodedServerLocation = null;
    serverPort = -1;

    keystoreData = null;
    keystoreManager = null;

    proxy = null;
    httpClient = null;
    if (connectionManager != null)
      connectionManager.shutdown();
    connectionManager = null;
  }

  /** Obtain the DistinguishedName for a given user logon name.
  *@param ctx is the ldap context to use.
  *@param userName (Domain Logon Name) is the user name or identifier.
  *@param searchBase (Full Domain Name for the search ie: DC=qa-ad-76,DC=metacarta,DC=com)
  *@return DistinguishedName for given domain user logon name. 
  * (Should throws an exception if user is not found.)
  */
  protected String getDistinguishedName(LdapContext ctx, String userName, String searchBase, String userACLsUsername)
    throws ManifoldCFException
  {
    String returnedAtts[] = {"distinguishedName"};
    String searchFilter = "(&(objectClass=user)(" + userACLsUsername + "=" + userName + "))";
    SearchControls searchCtls = new SearchControls();
    searchCtls.setReturningAttributes(returnedAtts);
    //Specify the search scope  
    searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
    searchCtls.setReturningAttributes(returnedAtts);

    try
    {
      NamingEnumeration answer = ctx.search(searchBase, searchFilter, searchCtls);
      while (answer.hasMoreElements())
      {
        SearchResult sr = (SearchResult)answer.next();
        Attributes attrs = sr.getAttributes();
        if (attrs != null)
        {
          String dn = attrs.get("distinguishedName").get().toString();
          return dn;
        }
      }
      return null;
    }
    catch (NamingException e)
    {
      throw new ManifoldCFException(e.getMessage(),e);
    }
  }
   
  /** LDAP escape a string.
  */
  protected static String ldapEscape(String input)
  {
    //Add escape sequence to all commas
    StringBuilder sb = new StringBuilder();
    int index = 0;
    while (true)
    {
      int oldIndex = index;
      index = input.indexOf(",",oldIndex);
      if (index == -1)
      {
        sb.append(input.substring(oldIndex));
        break;
      }
      sb.append(input.substring(oldIndex,index)).append("\\,");
      index++;
    }
    return sb.toString();
  }
    	
  /** Convert a binary SID to a string */
  protected static String sid2String(byte[] SID)
  {
    StringBuilder strSID = new StringBuilder("S");
    long version = SID[0];
    strSID.append("-").append(Long.toString(version));
    long authority = SID[4];
    for (int i = 0;i<4;i++)
    {
      authority <<= 8;
      authority += SID[4+i] & 0xFF;
    }
    strSID.append("-").append(Long.toString(authority));
    long count = SID[2];
    count <<= 8;
    count += SID[1] & 0xFF;
    for (int j=0;j<count;j++)
    {
      long rid = SID[11 + (j*4)] & 0xFF;
      for (int k=1;k<4;k++)
      {
        rid <<= 8;
        rid += SID[11-k + (j*4)] & 0xFF;
      }
      strSID.append("-").append(Long.toString(rid));
    }
    return strSID.toString();
  }

  /** Class representing the session information for a specific domain controller
  * connection.
  */
  protected static class DCSessionInfo
  {
    /** The initialized LDAP context (which functions as a session) */
    private LdapContext ctx = null;
    /** The time of last access to this ctx object */
    private long expiration = -1L;
    
    public DCSessionInfo()
    {
    }

    /** Initialize the session. */
    public LdapContext getADSession(String domainControllerName, DCConnectionParameters params)
      throws ManifoldCFException
    {
      String authentication = params.getAuthentication();
      String userName = params.getUserName();
      String password = params.getPassword();
      
      while (true)
      {
        if (ctx == null)
        {
          // Calculate the ldap url first
          String ldapURL = "ldap://" + domainControllerName + ":389";
          
          Hashtable env = new Hashtable();
          env.put(Context.INITIAL_CONTEXT_FACTORY,"com.sun.jndi.ldap.LdapCtxFactory");
          env.put(Context.SECURITY_AUTHENTICATION,authentication);      
          env.put(Context.SECURITY_PRINCIPAL,userName);
          env.put(Context.SECURITY_CREDENTIALS,password);
                                    
          //connect to my domain controller
          env.put(Context.PROVIDER_URL,ldapURL);
                    
          //specify attributes to be returned in binary format
          env.put("java.naming.ldap.attributes.binary","tokenGroups objectSid");
     
          // Now, try the connection...
          try
          {
            ctx = new InitialLdapContext(env,null);
            // If successful, break
            break;
          }
          catch (AuthenticationException e)
          {
            // This means we couldn't authenticate!
            throw new ManifoldCFException("Authentication problem authenticating admin user '"+userName+"': "+e.getMessage(),e);
          }
          catch (CommunicationException e)
          {
            // This means we couldn't connect, most likely
            throw new ManifoldCFException("Couldn't communicate with domain controller '"+domainControllerName+"': "+e.getMessage(),e);
          }
          catch (NamingException e)
          {
            throw new ManifoldCFException(e.getMessage(),e);
          }
        }
        else
        {
          // Attempt to reconnect.  I *hope* this is efficient and doesn't do unnecessary work.
          try
          {
            ctx.reconnect(null);
            // Break on apparent success
            break;
          }
          catch (AuthenticationException e)
          {
            // This means we couldn't authenticate!  Log it and retry creating a whole new context.
            Logging.authorityConnectors.warn("Reconnect: Authentication problem authenticating admin user '"+userName+"': "+e.getMessage(),e);
          }
          catch (CommunicationException e)
          {
            // This means we couldn't connect, most likely.  Log it and retry creating a whole new context.
            Logging.authorityConnectors.warn("Reconnect: Couldn't communicate with domain controller '"+domainControllerName+"': "+e.getMessage(),e);
          }
          catch (NamingException e)
          {
            Logging.authorityConnectors.warn("Reconnect: Naming exception: "+e.getMessage(),e);
          }
          
          // So we have no chance of leaking resources, attempt to close the context.
          closeConnection();
          // Loop back around to try our luck with a fresh connection.

        }
      }
      
      // Set the expiration time anew
      expiration = System.currentTimeMillis() + ADExpirationInterval;
      return ctx;
    }
    
    /** Close the connection handle. */
    protected void closeConnection()
    {
      if (ctx != null)
      {
        try
        {
          ctx.close();
        }
        catch (NamingException e)
        {
          // Eat this error
        }
        ctx = null;
        expiration = -1L;
      }
    }

    /** Close connection if it has expired. */
    protected void closeIfExpired(long currentTime)
    {
      if (expiration != -1L && currentTime > expiration)
        closeConnection();
    }

  }

  /** Decode a path item.
  */
  public static String pathItemDecode(String pathItem)
  {
    try
    {
      return java.net.URLDecoder.decode(pathItem.replaceAll("\\%20","+"),"utf-8");
    }
    catch (UnsupportedEncodingException e)
    {
      // Bad news, utf-8 not available!
      throw new RuntimeException("No utf-8 encoding available");
    }
  }

  /** Encode a path item.
  */
  public static String pathItemEncode(String pathItem)
  {
    try
    {
      String output = java.net.URLEncoder.encode(pathItem,"utf-8");
      return output.replaceAll("\\+","%20");
    }
    catch (UnsupportedEncodingException e)
    {
      // Bad news, utf-8 not available!
      throw new RuntimeException("No utf-8 encoding available");
    }
  }

  /** Given a path that is /-separated, and otherwise encoded, decode properly to convert to
  * unencoded form.
  */
  public static String decodePath(String relPath)
  {
    StringBuilder sb = new StringBuilder();
    String[] pathEntries = relPath.split("/");
    int k = 0;

    boolean isFirst = true;
    while (k < pathEntries.length)
    {
      if (isFirst)
        isFirst = false;
      else
        sb.append("/");
      sb.append(pathItemDecode(pathEntries[k++]));
    }
    return sb.toString();
  }

  /** Given a path that is /-separated, and otherwise unencoded, encode properly for an actual
  * URI
  */
  public static String encodePath(String relPath)
  {
    StringBuilder sb = new StringBuilder();
    String[] pathEntries = relPath.split("/");
    int k = 0;

    boolean isFirst = true;
    while (k < pathEntries.length)
    {
      if (isFirst)
        isFirst = false;
      else
        sb.append("/");
      sb.append(pathItemEncode(pathEntries[k++]));
    }
    return sb.toString();
  }

  /** Class describing a domain suffix and corresponding domain controller name rule.
  */
  protected static class DCRule
  {
    private String suffix;
    private String domainControllerName;
    
    public DCRule(String suffix, String domainControllerName)
    {
      this.suffix = suffix;
      this.domainControllerName = domainControllerName;
    }
    
    public String getSuffix()
    {
      return suffix;
    }
    
    public String getDomainControllerName()
    {
      return domainControllerName;
    }
  }
  
  /** Class describing the connection parameters to a domain controller.
  */
  protected static class DCConnectionParameters
  {
    private String userName;
    private String password;
    private String authentication;
    private String userACLsUsername;

    public DCConnectionParameters(String userName, String password, String authentication, String userACLsUsername)
    {
      this.userName = userName;
      this.password = password;
      this.authentication = authentication;
      this.userACLsUsername = userACLsUsername;
    }
    
    public String getUserName()
    {
      return userName;
    }
    
    public String getPassword()
    {
      return password;
    }
    
    public String getAuthentication()
    {
      return authentication;
    }
    
    public String getUserACLsUsername()
    {
      return userACLsUsername;
    }
  }
  
  protected static StringSet emptyStringSet = new StringSet();
  
  /** This is the cache object descriptor for cached access tokens from
  * this connector.
  */
  protected static class AuthorizationResponseDescription extends org.apache.manifoldcf.core.cachemanager.BaseDescription
  {
    /** The user name */
    protected String userName;
    /** Connection parameters */
    protected Map<String,DCConnectionParameters> dcConnectionParams;
    /** Rules */
    protected List<DCRule> dcRules;
    /** The response lifetime */
    protected long responseLifetime;
    /** The expiration time */
    protected long expirationTime = -1;
    
    /** Constructor. */
    public AuthorizationResponseDescription(String userName, Map<String,DCConnectionParameters> dcConnectionParams,
      List<DCRule> dcRules, long responseLifetime, int LRUsize)
    {
      super("SharePointAuthority",LRUsize);
      this.userName = userName;
      this.dcConnectionParams = dcConnectionParams;
      this.dcRules = dcRules;
      this.responseLifetime = responseLifetime;
    }

    /** Return the invalidation keys for this object. */
    public StringSet getObjectKeys()
    {
      return emptyStringSet;
    }

    /** Get the critical section name, used for synchronizing the creation of the object */
    public String getCriticalSectionName()
    {
      StringBuilder sb = new StringBuilder(getClass().getName());
      sb.append("-").append(userName);
      for (DCRule rule : dcRules)
      {
        sb.append("-").append(rule.getSuffix());
        String domainController = rule.getDomainControllerName();
        DCConnectionParameters params = dcConnectionParams.get(domainController);
        sb.append("-").append(domainController).append("-").append(params.getUserName()).append("-").append(params.getPassword());
      }
      return sb.toString();
    }

    /** Return the object expiration interval */
    public long getObjectExpirationTime(long currentTime)
    {
      if (expirationTime == -1)
        expirationTime = currentTime + responseLifetime;
      return expirationTime;
    }

    public int hashCode()
    {
      int rval = userName.hashCode();
      for (DCRule rule : dcRules)
      {
        String domainController = rule.getDomainControllerName();
        DCConnectionParameters params = dcConnectionParams.get(domainController);
        rval += rule.getSuffix().hashCode() + domainController.hashCode() + params.getUserName().hashCode() + params.getPassword().hashCode();
      }
      return rval;
    }
    
    public boolean equals(Object o)
    {
      if (!(o instanceof AuthorizationResponseDescription))
        return false;
      AuthorizationResponseDescription ard = (AuthorizationResponseDescription)o;
      if (!ard.userName.equals(userName))
        return false;
      if (ard.dcRules.size() != dcRules.size())
        return false;
      for (int i = 0 ; i < dcRules.size() ; i++)
      {
        DCRule rule = dcRules.get(i);
        DCRule ardRule = ard.dcRules.get(i);
        if (!rule.getSuffix().equals(ardRule.getSuffix()) || !rule.getDomainControllerName().equals(ardRule.getDomainControllerName()))
          return false;
        String domainController = rule.getDomainControllerName();
        DCConnectionParameters params = dcConnectionParams.get(domainController);
        DCConnectionParameters ardParams = ard.dcConnectionParams.get(domainController);
        if (!params.getUserName().equals(ardParams.getUserName()) || !params.getPassword().equals(ardParams.getPassword()))
          return false;
      }
      return true;
    }
    
  }
  
}

