/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.security;

import co.cask.cdap.client.AuthorizationClient;
import co.cask.cdap.client.NamespaceClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.proto.ConfigEntry;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.DatasetModuleId;
import co.cask.cdap.proto.id.DatasetTypeId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.InstanceId;
import co.cask.cdap.proto.id.KerberosPrincipalId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.SecureKeyId;
import co.cask.cdap.proto.id.StreamId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Role;
import co.cask.cdap.security.authorization.ranger.commons.RangerCommon;
import co.cask.cdap.security.authorization.sentry.model.Application;
import co.cask.cdap.security.authorization.sentry.model.Artifact;
import co.cask.cdap.security.authorization.sentry.model.Authorizable;
import co.cask.cdap.security.authorization.sentry.model.Dataset;
import co.cask.cdap.security.authorization.sentry.model.DatasetModule;
import co.cask.cdap.security.authorization.sentry.model.DatasetType;
import co.cask.cdap.security.authorization.sentry.model.Instance;
import co.cask.cdap.security.authorization.sentry.model.Namespace;
import co.cask.cdap.security.authorization.sentry.model.Program;
import co.cask.cdap.security.authorization.sentry.model.SecureKey;
import co.cask.cdap.security.authorization.sentry.model.Stream;
import co.cask.cdap.test.AudiTestBase;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.GrantRevokeRequest;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.apache.sentry.provider.db.SentryNoSuchObjectException;
import org.apache.sentry.provider.db.generic.service.thrift.SentryGenericServiceClient;
import org.apache.sentry.provider.db.generic.service.thrift.TAuthorizable;
import org.apache.sentry.provider.db.generic.service.thrift.TSentryGrantOption;
import org.apache.sentry.provider.db.generic.service.thrift.TSentryPrivilege;
import org.apache.sentry.service.thrift.ServiceConstants;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

/**
 * Authorization test base for all authorization tests
 */
public abstract class AuthorizationTestBase extends AudiTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationTestBase.class);
  protected static final Gson GSON = new GsonBuilder().enableComplexMapKeySerialization().create();

  protected static final String ALICE = "alice";
  protected static final String BOB = "bob";
  protected static final String CAROL = "carol";
  protected static final String EVE = "eve";
  protected static final String ADMIN_USER = "cdapitn";
  protected static final String PASSWORD_SUFFIX = "password";
  protected static final String VERSION = "1.0.0";
  protected static final String NO_ACCESS_MSG = "does not have privileges to access entity";
  protected static final String NO_PRIVILEGE_MESG = "is not authorized to perform action";

  private static final String COMPONENT = "cdap";
  private static final String INSTANCE_NAME = "cdap";
  private static final Role DUMMY_ROLE = new Role("dummy");

  // TODO: Remove this when we migrate to wildcard privilege
  protected Set<EntityId> cleanUpEntities;
  protected SentryGenericServiceClient sentryClient;
  public RangerBasePlugin rangerPlugin;
  RangerAdminClient rangerAdminClient;
  private AuthorizationClient authorizationClient;

  // General test namespace
  protected NamespaceMeta testNamespace = getNamespaceMeta(new NamespaceId("authorization"), null, null,
                                                           null, null, null, null);

  @Override
  public void setUp() throws Exception {
   // sentryClient = SentryGenericServiceClientFactory.create(getSentryConfig());
    initRangerPlugin();
    // TODO: remove this once caching in sentry is fixed
    ClientConfig adminConfig = getClientConfig(fetchAccessToken(ADMIN_USER, ADMIN_USER));
    RESTClient adminClient = new RESTClient(adminConfig);
    authorizationClient = new AuthorizationClient(adminConfig, adminClient);

    userGrant("cdap", NamespaceId.DEFAULT, Action.ADMIN);
//    userGrant("cdap", new NamespaceId("authorization"), Action.ADMIN);

    userGrant(ADMIN_USER, NamespaceId.DEFAULT, Action.ADMIN);
    invalidateCache();
    super.setUp();
    userRevoke(ADMIN_USER);
    invalidateCache();
    cleanUpEntities = new HashSet<>();
  }

  @Before
  public void setup() throws Exception {
    ConfigEntry configEntry = this.getMetaClient().getCDAPConfig().get("security.authorization.enabled");
    Preconditions.checkNotNull(configEntry, "Missing key from CDAP Configuration: %s",
                               "security.authorization.enabled");
    Preconditions.checkState(Boolean.parseBoolean(configEntry.getValue()), "Authorization not enabled.");
  }

  @Override
  public void tearDown() throws Exception {
    // we have to grant ADMIN privileges to all clean up entites such that these entities can be deleted
    for (EntityId entityId : cleanUpEntities) {
      userGrant(ADMIN_USER, entityId, Action.ADMIN);
    }
    userGrant(ADMIN_USER, testNamespace.getNamespaceId(), Action.ADMIN);
    userGrant(ADMIN_USER, NamespaceId.DEFAULT, Action.ADMIN);
    invalidateCache();
    // teardown in parent deletes all entities
    super.tearDown();
    // reset the test by revoking privileges from all users.
    LOG.error("Yaojie - start revoking");
    userRevoke(ADMIN_USER);
    userRevoke(ALICE);
    userRevoke(BOB);
    userRevoke(CAROL);
    userRevoke(EVE);
    LOG.error("Yaojie - finished revoking");
    invalidateCache();
//    sentryClient.close();
    rangerPlugin.cleanup();
  }

  protected NamespaceId createAndRegisterNamespace(NamespaceMeta namespaceMeta, ClientConfig config,
                                                   RESTClient client) throws Exception {
    try {
      new NamespaceClient(config, client).create(namespaceMeta);
      cleanUpEntities.add(namespaceMeta.getNamespaceId());
    } finally {
      registerForDeletion(namespaceMeta.getNamespaceId());
    }
    return namespaceMeta.getNamespaceId();
  }

  /**
   * Grants action privilege to user on entityId. Creates a role for the user. Grant action privilege
   * on that role, and add the role to the group the user belongs to. All done through sentry.
   * @param user The user we want to grant privilege to.
   * @param entityId The entity we want to grant privilege on.
   * @param action The privilege we want to grant.
   */
  protected void userGrant(String user, EntityId entityId, Action action) throws Exception {
   // roleGrant(user, entityId, action, null);
    RangerDefaultAuditHandler auditHandler = new RangerDefaultAuditHandler();
    rangerPlugin.setResultProcessor(auditHandler);

    GrantRevokeRequest request = new GrantRevokeRequest();
    request.setGrantor(ADMIN_USER);
    request.setAccessTypes(ImmutableSet.of(action.name().toLowerCase()));
    request.setUsers(ImmutableSet.of(user));
    request.setIsRecursive(false);

    Map<String, String> resource = new HashMap<>();
    setRangerResource(entityId, resource);
    request.setResource(resource);
    rangerPlugin.grantAccess(request, auditHandler);
  }

  protected void roleGrant(String role, EntityId entityId, Action action,
                           @Nullable String groupName) throws Exception {
    // create role and add to group
    // TODO: use a different user as Sentry Admin (neither CDAP, nor an user used in our tests)
    sentryClient.createRoleIfNotExist(ADMIN_USER, role, COMPONENT);
    sentryClient.addRoleToGroups(ADMIN_USER, role, COMPONENT,
                                 groupName == null ? Sets.newHashSet(role) : Sets.newHashSet(groupName));

    // create authorizable list
    List<TAuthorizable> authorizables = toTAuthorizable(entityId);
    TSentryPrivilege privilege = new TSentryPrivilege(COMPONENT, INSTANCE_NAME, authorizables, action.name());
    privilege.setGrantOption(TSentryGrantOption.TRUE);
    sentryClient.grantPrivilege(ADMIN_USER, role, COMPONENT, privilege);
  }

  protected void invalidateCache() throws Exception {
    // TODO: Hack to invalidate cache in sentry authorizer. Remove once cache problem is solved.
  //  authorizationClient.dropRole(DUMMY_ROLE);
    TimeUnit.SECONDS.sleep(5);
  }

  /**
   * Revokes all privileges from user. Deletes the user role through sentry.
   *
   * @param user The user we want to revoke privilege from.
   */
  protected void userRevoke(String user) throws Exception {
    ServicePolicies servicePolicies = rangerAdminClient.getServicePoliciesIfUpdated(-1, -1);

    Map<RangerPolicy, Set<String>> userPolicies = new HashMap<>();
    for (RangerPolicy policy : servicePolicies.getPolicies()) {
      for (RangerPolicy.RangerPolicyItem policyItem : policy.getPolicyItems()) {
        if (Sets.newHashSet(policyItem.getUsers()).contains(user)) {
          policyItem.setUsers(Collections.singletonList(user));
          userPolicies.put(policy,
                           Sets.newHashSet(
                             Iterables.transform(policyItem.getAccesses(),
                                                 new Function<RangerPolicy.RangerPolicyItemAccess, String>() {
                                                   @Override
                                                   public String apply(RangerPolicy.RangerPolicyItemAccess input) {
                                                     return input.getType();
                                                   }
                                                 })));
        }
      }
    }

    for (Map.Entry<RangerPolicy, Set<String>> entry : userPolicies.entrySet()) {
      RangerPolicy userPolicy = entry.getKey();
      GrantRevokeRequest request = new GrantRevokeRequest();
      request.setGrantor(ADMIN_USER);
      request.setUsers(ImmutableSet.of(user));

      Map<String, String> resource =
        Maps.transformEntries(userPolicy.getResources(),
                              new Maps.EntryTransformer<String, RangerPolicy.RangerPolicyResource, String>() {
                                @Override
                                public String transformEntry(String key, RangerPolicy.RangerPolicyResource value) {
                                  return value.getValues().iterator().next();
                                }
                              });
      request.setResource(resource);
      request.setAccessTypes(entry.getValue());

      RangerDefaultAuditHandler auditHandler = new RangerDefaultAuditHandler();
      rangerPlugin.setResultProcessor(auditHandler);
      rangerPlugin.revokeAccess(request, auditHandler);
    }
  }

  protected void userRevoke(String user, EntityId entityId, Action action) throws Exception {
    // create authorizable list
    List<TAuthorizable> authorizables = toTAuthorizable(entityId);
    TSentryPrivilege privilege = new TSentryPrivilege(COMPONENT, INSTANCE_NAME, authorizables, action.name());
    privilege.setGrantOption(TSentryGrantOption.TRUE);
    sentryClient.revokePrivilege(ADMIN_USER, user, COMPONENT, privilege);
  }

  protected void roleRevoke(String role, @Nullable String groupName) throws Exception {
    try {
      sentryClient.deleteRoleToGroups(ADMIN_USER, role, COMPONENT,
                                      groupName == null ? Sets.newHashSet(role) : Sets.newHashSet(groupName));
    } catch (SentryNoSuchObjectException e) {
      // skip a role that hasn't been added to the user
    } finally {
      sentryClient.dropRoleIfExists(ADMIN_USER, role, COMPONENT);
    }
  }

  protected NamespaceMeta getNamespaceMeta(NamespaceId namespaceId, @Nullable String principal,
                                           @Nullable String groupName, @Nullable String keytabURI,
                                           @Nullable String rootDirectory, @Nullable String hbaseNamespace,
                                           @Nullable String hiveDatabase) {
    return new NamespaceMeta.Builder()
      .setName(namespaceId)
      .setDescription("Namespace for authorization test")
      .setPrincipal(principal)
      .setGroupName(groupName)
      .setKeytabURI(keytabURI)
      .setRootDirectory(rootDirectory)
      .setHBaseNamespace(hbaseNamespace)
      .setHiveDatabase(hiveDatabase)
      .build();
  }

  private Configuration getSentryConfig() throws IOException, LoginException, URISyntaxException {

    String hostUri = super.getInstanceURI();
    String sentryRpcAddr = new URI(hostUri).getHost();
    String sentryPrincipal = "sentry/" + sentryRpcAddr + "@CONTINUUITY.NET";
    String sentryRpcPort = "8038";

    Configuration conf = new Configuration(false);
    conf.clear();
    conf.set(ServiceConstants.ServerConfig.SECURITY_MODE, ServiceConstants.ServerConfig.SECURITY_MODE_KERBEROS);
    conf.set(ServiceConstants.ServerConfig.PRINCIPAL, sentryPrincipal);
    conf.set(ServiceConstants.ClientConfig.SERVER_RPC_ADDRESS, sentryRpcAddr);
    conf.set(ServiceConstants.ClientConfig.SERVER_RPC_PORT, sentryRpcPort);

    // Log in to Kerberos
    UserGroupInformation.setConfiguration(conf);
    LoginContext lc = kinit();
    UserGroupInformation.loginUserFromSubject(lc.getSubject());
    return conf;
  }

  private static LoginContext kinit() throws LoginException {
    LoginContext lc = new LoginContext(BasicAuthorizationTest.class.getSimpleName(), new CallbackHandler() {
      public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback c : callbacks) {
          if (c instanceof NameCallback) {
            ((NameCallback) c).setName(ADMIN_USER);
          }
          if (c instanceof PasswordCallback) {
            ((PasswordCallback) c).setPassword(ADMIN_USER.toCharArray());
          }
        }
      }
    });
    lc.login();
    return lc;
  }

  private List<TAuthorizable> toTAuthorizable(EntityId entityId) {
    List<org.apache.sentry.core.common.Authorizable> authorizables = toSentryAuthorizables(entityId);
    List<TAuthorizable> tAuthorizables = new ArrayList<>();
    for (org.apache.sentry.core.common.Authorizable authorizable : authorizables) {
      tAuthorizables.add(new TAuthorizable(authorizable.getTypeName(), authorizable.getName()));
    }
    return tAuthorizables;
  }

  @VisibleForTesting
  List<org.apache.sentry.core.common.Authorizable> toSentryAuthorizables(final EntityId entityId) {
    List<org.apache.sentry.core.common.Authorizable> authorizables = new LinkedList<>();
    toAuthorizables(entityId, authorizables);
    return authorizables;
  }

  private void toAuthorizables(EntityId entityId, List<? super Authorizable> authorizables) {
    EntityType entityType = entityId.getEntityType();
    switch (entityType) {
      case INSTANCE:
        authorizables.add(new Instance(((InstanceId) entityId).getInstance()));
        break;
      case NAMESPACE:
        toAuthorizables(new InstanceId(INSTANCE_NAME), authorizables);
        authorizables.add(new Namespace(((NamespaceId) entityId).getNamespace()));
        break;
      case ARTIFACT:
        ArtifactId artifactId = (ArtifactId) entityId;
        toAuthorizables(artifactId.getParent(), authorizables);
        authorizables.add(new Artifact(artifactId.getArtifact(), artifactId.getVersion()));
        break;
      case APPLICATION:
        ApplicationId applicationId = (ApplicationId) entityId;
        toAuthorizables(applicationId.getParent(), authorizables);
        authorizables.add(new Application(applicationId.getApplication()));
        break;
      case DATASET:
        DatasetId dataset = (DatasetId) entityId;
        toAuthorizables(dataset.getParent(), authorizables);
        authorizables.add(new Dataset(dataset.getDataset()));
        break;
      case DATASET_MODULE:
        DatasetModuleId datasetModuleId = (DatasetModuleId) entityId;
        toAuthorizables(datasetModuleId.getParent(), authorizables);
        authorizables.add(new DatasetModule(datasetModuleId.getModule()));
        break;
      case DATASET_TYPE:
        DatasetTypeId datasetTypeId = (DatasetTypeId) entityId;
        toAuthorizables(datasetTypeId.getParent(), authorizables);
        authorizables.add(new DatasetType(datasetTypeId.getType()));
        break;
      case STREAM:
        StreamId streamId = (StreamId) entityId;
        toAuthorizables(streamId.getParent(), authorizables);
        authorizables.add(new Stream((streamId).getStream()));
        break;
      case PROGRAM:
        ProgramId programId = (ProgramId) entityId;
        toAuthorizables(programId.getParent(), authorizables);
        authorizables.add(new Program(programId.getType(), programId.getProgram()));
        break;
      case SECUREKEY:
        SecureKeyId secureKeyId = (SecureKeyId) entityId;
        toAuthorizables(secureKeyId.getParent(), authorizables);
        authorizables.add(new SecureKey(secureKeyId.getName()));
        break;
      case KERBEROSPRINCIPAL:
        KerberosPrincipalId principalId = (KerberosPrincipalId) entityId;
        toAuthorizables(new InstanceId(INSTANCE_NAME), authorizables);
        authorizables.add(new co.cask.cdap.security.authorization.sentry.model.Principal(principalId.getPrincipal()));
        break;
      default:
        throw new IllegalArgumentException(String.format("The entity %s is of unknown type %s", entityId, entityType));
    }
  }

  private void initRangerPlugin() throws Exception {
    Configuration conf = new Configuration(false);
    conf.clear();
    conf.set("hadoop.security.authentication", "kerberos");

    UserGroupInformation.setConfiguration(conf);
    LoginContext lc = kinit();
    UserGroupInformation.loginUserFromSubject(lc.getSubject());

    UserGroupInformation ugi = UserGroupInformation.getLoginUser();
    Preconditions.checkNotNull(ugi, "Kerberos login information is not available. UserGroupInformation is null");
    MiscUtil.setUGILoginUser(null, lc.getSubject());

    // the string name here should not be changed as this uniquely identifies the plugin in ranger
    rangerPlugin = new RangerBasePlugin("cdap", "cdap");
    rangerPlugin.init();
    RangerDefaultAuditHandler auditHandler = new RangerDefaultAuditHandler();
    rangerPlugin.setResultProcessor(auditHandler);

    String propertyPrefix    = "ranger.plugin." + "cdap";
    rangerAdminClient = RangerBasePlugin.createAdminClient("cdapdev",
                                                                             "cdap", propertyPrefix);


//    ServicePolicies servicePolicies = rangerAdminClient.getServicePoliciesIfUpdated(-1, -1);
//
//
//    RangerServiceDefHelper serviceDefHelper = new RangerServiceDefHelper(servicePolicies.getServiceDef(), false);
//    Set<List<RangerServiceDef.RangerResourceDef>> validResourceHierarchies = serviceDefHelper.getResourceHierarchies(RangerPolicy.POLICY_TYPE_ACCESS);
//    System.out.println(validResourceHierarchies);
  }

  /**
   * Sets the Ranger resource appropriately depending on the given entityId
   */
  public void setRangerResource(EntityId entityId, Map<String, String> resource) {
    EntityType entityType = entityId.getEntityType();
    switch (entityType) {
      case INSTANCE:
        resource.put(RangerCommon.KEY_INSTANCE, "cdap");
        break;
      case NAMESPACE:
        setRangerResource(new InstanceId(INSTANCE_NAME), resource);
        resource.put(RangerCommon.KEY_NAMESPACE, ((NamespaceId) entityId).getNamespace());
        break;
      case ARTIFACT:
        ArtifactId artifactId = (ArtifactId) entityId;
        setRangerResource(artifactId.getParent(), resource);
        resource.put(RangerCommon.KEY_ARTIFACT, artifactId.getArtifact());
        break;
      case APPLICATION:
        ApplicationId applicationId = (ApplicationId) entityId;
        setRangerResource(applicationId.getParent(), resource);
        resource.put(RangerCommon.KEY_APPLICATION, applicationId.getApplication());
        break;
      case DATASET:
        DatasetId dataset = (DatasetId) entityId;
        setRangerResource(dataset.getParent(), resource);
        resource.put(RangerCommon.KEY_DATASET, dataset.getDataset());
        break;
      case DATASET_MODULE:
        DatasetModuleId datasetModuleId = (DatasetModuleId) entityId;
        setRangerResource(datasetModuleId.getParent(), resource);
        resource.put(RangerCommon.KEY_DATASET_MODULE, datasetModuleId.getModule());
        break;
      case DATASET_TYPE:
        DatasetTypeId datasetTypeId = (DatasetTypeId) entityId;
        setRangerResource(datasetTypeId.getParent(), resource);
        resource.put(RangerCommon.KEY_DATASET_TYPE, datasetTypeId.getType());
        break;
      case STREAM:
        StreamId streamId = (StreamId) entityId;
        setRangerResource(streamId.getParent(), resource);
        resource.put(RangerCommon.KEY_STREAM, streamId.getStream());
        break;
      case PROGRAM:
        ProgramId programId = (ProgramId) entityId;
        setRangerResource(programId.getParent(), resource);
        resource.put(RangerCommon.KEY_PROGRAM, programId.getType() +
          RangerCommon.RESOURCE_SEPARATOR + programId.getProgram());
        break;
      case SECUREKEY:
        SecureKeyId secureKeyId = (SecureKeyId) entityId;
        setRangerResource(secureKeyId.getParent(), resource);
        resource.put(RangerCommon.KEY_SECUREKEY, secureKeyId.getName());
        break;
      case KERBEROSPRINCIPAL:
        setRangerResource(new InstanceId(INSTANCE_NAME), resource);
        // TODO: use KEY_PRINCIPAL after BA build is done
        resource.put(RangerCommon.KEY_PRINCIPAL,
                     ((KerberosPrincipalId) entityId).getPrincipal());
        break;
      default:
        throw new IllegalArgumentException(String.format("The entity %s is of unknown type %s", entityId, entityType));
    }
  }

  // TODO: Remove this when we migrate to wildcard privilege
  protected void setUpPrivilegeAndRegisterForDeletion(String user,
                                                      Map<EntityId, Set<Action>> neededPrivileges) throws Exception {
    for (Map.Entry<EntityId, Set<Action>> privilege : neededPrivileges.entrySet()) {
      for (Action action : privilege.getValue()) {
        userGrant(user, privilege.getKey(), action);
        cleanUpEntities.add(privilege.getKey());
      }
    }
  }
}
