/*
 * Copyright 2011 Vincent Behar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rundeck.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.rundeck.api.RundeckApiException.RundeckApiLoginException;
import org.rundeck.api.RundeckApiException.RundeckApiTokenException;
import org.rundeck.api.domain.ArchiveImport;
import org.rundeck.api.domain.ConfigProperty;
import org.rundeck.api.domain.DeleteExecutionsResponse;
import org.rundeck.api.domain.KeyResource;
import org.rundeck.api.domain.ProjectConfig;
import org.rundeck.api.domain.RundeckAbort;
import org.rundeck.api.domain.RundeckExecution;
import org.rundeck.api.domain.RundeckExecution.ExecutionStatus;
import org.rundeck.api.domain.RundeckExecutionState;
import org.rundeck.api.domain.RundeckHistory;
import org.rundeck.api.domain.RundeckJob;
import org.rundeck.api.domain.RundeckJobDeleteBulk;
import org.rundeck.api.domain.RundeckJobsImportResult;
import org.rundeck.api.domain.RundeckNode;
import org.rundeck.api.domain.RundeckOutput;
import org.rundeck.api.domain.RundeckProject;
import org.rundeck.api.domain.RundeckSystemInfo;
import org.rundeck.api.domain.RundeckToken;
import org.rundeck.api.generator.DeleteExecutionsGenerator;
import org.rundeck.api.generator.ProjectConfigGenerator;
import org.rundeck.api.generator.ProjectConfigPropertyGenerator;
import org.rundeck.api.generator.ProjectGenerator;
import org.rundeck.api.generator.TokenGenerator;
import org.rundeck.api.parser.AbortParser;
import org.rundeck.api.parser.ArchiveImportParser;
import org.rundeck.api.parser.BulkDeleteParser;
import org.rundeck.api.parser.DeleteExecutionsResponseParser;
import org.rundeck.api.parser.ExecutionParser;
import org.rundeck.api.parser.ExecutionStateParser;
import org.rundeck.api.parser.HistoryParser;
import org.rundeck.api.parser.JobParser;
import org.rundeck.api.parser.JobsImportResultParser;
import org.rundeck.api.parser.ListParser;
import org.rundeck.api.parser.NodeParser;
import org.rundeck.api.parser.OutputEntryParser;
import org.rundeck.api.parser.OutputEntryParserV5;
import org.rundeck.api.parser.OutputParser;
import org.rundeck.api.parser.PagedResultParser;
import org.rundeck.api.parser.ProjectConfigParser;
import org.rundeck.api.parser.ProjectConfigPropertyParser;
import org.rundeck.api.parser.ProjectParser;
import org.rundeck.api.parser.ProjectParserV11;
import org.rundeck.api.parser.RundeckTokenParser;
import org.rundeck.api.parser.RundeckTokenParserV19;
import org.rundeck.api.parser.SSHKeyResourceParser;
import org.rundeck.api.parser.SystemInfoParser;
import org.rundeck.api.query.ExecutionQuery;
import org.rundeck.api.util.AssertUtil;
import org.rundeck.api.util.PagedResults;
import org.rundeck.api.util.ParametersUtil;

/**
 * Rundeck API client.
 * <p>
 * There are three methods for authentication : login-based or token-based or session-based.
 * Login authentication requires
 * both a "login" and a "password". Token-based requires a "token" (also called "auth-token"). See the Rundeck
 * documentation for generating such a token.</p>
 * <p>
 *     Session-based authentication allows re-use of a previous login session. See {@link #testAuth()}.
 * </p>
 * <br>
 * Usage : <br>
 * <code>
 * <pre>
 * // using login-based authentication :
 * RundeckClient rundeck = RundeckClient.builder()
 *                           .url("http://localhost:4440")
 *                           .login("admin", "admin").build();
 * // or for a token-based authentication :
 * RundeckClient rundeck = RundeckClient.builder()
 *                           .url("http://localhost:4440")
 *                           .token("PDDNKo5VE29kpk4prOUDr2rsKdRkEvsD").build();
 *
 * List&lt;RundeckProject&gt; projects = rundeck.getProjects();
 *
 * RundeckJob job = rundeck.findJob("my-project", "main-group/sub-group", "job-name");
 * RundeckExecution execution = rundeck.triggerJob(job.getId(),
 *                                                 new OptionsBuilder().addOption("version", "1.2.0").toProperties());
 *
 * List&lt;RundeckExecution&gt; runningExecutions = rundeck.getRunningExecutions("my-project");
 *
 * rundeck.exportJobsToFile("/tmp/jobs.xml", FileType.XML, "my-project");
 * rundeck.importJobs("/tmp/jobs.xml", FileType.XML);
 * </pre>
 * </code>
 *
 * @author Vincent Behar
 */
public class RundeckClient implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final String JOBS_IMPORT = "/jobs/import";
    public static final String STORAGE_ROOT_PATH = "/storage/";
    public static final String STORAGE_KEYS_PATH = "keys/";

    /**
     * Supported version numbers
     */
    public static enum Version {
        V5(5),
        V6(6),
        V7(7),
        V8(8),
        V9(9),
        V10(10),
        V11(11),
        V12(12),
        V13(13),
        V14(14),
        V15(15),
        V16(16),
        V17(17),
        V18(18),
        V19(19),
        V20(20),
        V21(21),
        ;

        private int versionNumber;

        Version(final int i) {
            versionNumber = i;
        }

        public int getVersionNumber() {
            return versionNumber;
        }
    }
    /** Version of the API supported */
    public static final transient int API_VERSION = Version.V21.getVersionNumber();

    private static final String API = "/api/";

    /** End-point of the API */
    public static final transient String API_ENDPOINT = API + API_VERSION;

    /** Default value for the "pooling interval" used when running jobs/commands/scripts */
    public static final transient long DEFAULT_POOLING_INTERVAL = 5;

    /** Default unit of the "pooling interval" used when running jobs/commands/scripts */
    public static final TimeUnit DEFAULT_POOLING_UNIT = TimeUnit.SECONDS;

    /** URL of the Rundeck instance ("http://localhost:4440", "http://rundeck.your-compagny.com/", etc) */
    private final String url;

    private int apiVersion = API_VERSION;

    private String token;

    private String login;

    private String password;

    private String sessionID;
    private boolean sslHostnameVerifyAllowAll = false;
    private boolean sslCertificateTrustAllowSelfSigned = false;
    private boolean systemProxyEnabled = false;
    private boolean useIntermediateStreamFile = false;

    boolean isUseIntermediateStreamFile() {
        return useIntermediateStreamFile;
    }

    void setUseIntermediateStreamFile(boolean useIntermediateStreamFile) {
        this.useIntermediateStreamFile = useIntermediateStreamFile;
    }

    void setToken(String token) {
        this.token = token;
    }

    void setLogin(String login) {
        this.login = login;
    }

    void setPassword(String password) {
        this.password = password;
    }

    void setSessionID(String sessionID) {
        this.sessionID = sessionID;
    }

    int getApiVersion() {
        return (apiVersion > 0 ? apiVersion : API_VERSION);
    }
    boolean isApiAtLeast(Version version){
        return getApiVersion()>=version.getVersionNumber();
    }

    void setApiVersion(int apiVersion) {
        this.apiVersion = (apiVersion > 0 ? apiVersion : API_VERSION);
    }

    void setApiVersion(Version apiVersion) {
        setApiVersion(apiVersion.getVersionNumber());
    }

    String getApiEndpoint() {
        return API + getApiVersion();
    }

    boolean isSslHostnameVerifyAllowAll() {
        return sslHostnameVerifyAllowAll;
    }

    void setSslHostnameVerifyAllowAll(boolean sslHostnameVerifyAllowAll) {
        this.sslHostnameVerifyAllowAll = sslHostnameVerifyAllowAll;
    }

    boolean isSslCertificateTrustAllowSelfSigned() {
        return sslCertificateTrustAllowSelfSigned;
    }

    void setSslCertificateTrustAllowSelfSigned(boolean sslCertificateTrustAllowSelfSigned) {
        this.sslCertificateTrustAllowSelfSigned = sslCertificateTrustAllowSelfSigned;
    }
    boolean isSystemProxyEnabled() {
        return systemProxyEnabled;
    }

    void setSystemProxyEnabled(boolean systemProxyEnabled) {
        this.systemProxyEnabled = systemProxyEnabled;
    }


    /**
     * Used by RundeckClientBuilder
     */
    RundeckClient(final String url) throws IllegalArgumentException {
        AssertUtil.notBlank(url, "The Rundeck URL is mandatory !");
        this.url=url;
    }

    /**
     * Create a builder for RundeckClient
     */
    public static RundeckClientBuilder builder() {
        return new RundeckClientBuilder();
    }

    /**
     * Try to "ping" the Rundeck instance to see if it is alive
     *
     * @throws RundeckApiException if the ping fails
     */
    public void ping() throws RundeckApiException {
        new ApiCall(this).ping();
    }

    /**
     * Test the authentication on the Rundeck instance.
     *
     * @return sessionID if doing username+password login and it succeeded
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     */
    public String testAuth() throws RundeckApiLoginException, RundeckApiTokenException {
        return (new ApiCall(this)).testAuth();
    }


    /*
     * Projects
     */

    private ProjectParser createProjectParser() {
        return createProjectParser(null);
    }

    private ProjectParser createProjectParser(final String xpath) {
        return new ProjectParserV11(xpath);
    }

    /**
     * List all projects
     *
     * @return a {@link List} of {@link RundeckProject} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     */
    public List<RundeckProject> getProjects() throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException {
        return new ApiCall(this).get(new ApiPathBuilder("/projects"),
                                     new ListParser<>(
                                             createProjectParser(),
                                             "/projects/project"
                                     ));
    }

    /**
     * Get the definition of a single project, identified by the given name
     *
     * @param projectName name of the project - mandatory
     * @return a {@link RundeckProject} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public RundeckProject getProject(String projectName) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(projectName, "projectName is mandatory to get the details of a project !");
        return new ApiCall(this).get(new ApiPathBuilder("/project/", projectName),
                createProjectParser(
                        (getApiVersion() < Version.V11.getVersionNumber()
                                ? "/projects/project"
                                : "/project"
                        )));
    }

    /**
     * Create a new project, and return the new definition
     *
     * @param projectName name of the project - mandatory
     * @param configuration project configuration properties
     *
     * @return a {@link RundeckProject} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public RundeckProject createProject(String projectName, Map<String, String> configuration) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to create a project !");
        return new ApiCall(this)
                .post(new ApiPathBuilder("/projects").xml(
                        projectDocument(projectName, configuration)
                ), createProjectParser(
                        (getApiVersion() < Version.V11.getVersionNumber()
                                ? "/projects/project"
                                : "/project"
                        )));
    }
    /**
     * Delete a project
     *
     * @param projectName name of the project - mandatory
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public void deleteProject(String projectName) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to create a project !");
        new ApiCall(this).delete(new ApiPathBuilder("/project/", projectName));
    }
    /**
     * Convenience method to export the archive of a project to the specified file.
     *
     * @param projectName name of the project - mandatory
     * @param out         file to write to
     * @return number of bytes written to the stream
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public int exportProject(final String projectName, final File out) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException, IOException {
        final FileOutputStream fileOutputStream = new FileOutputStream(out);
        try {
            return exportProject(projectName, fileOutputStream);
        }finally {
            fileOutputStream.close();
        }
    }
    /**
     * Export the archive of a project to the specified outputstream
     *
     * @param projectName name of the project - mandatory
     * @return number of bytes written to the stream
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public int exportProject(String projectName, OutputStream out) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException, IOException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to export a project archive!");
        return new ApiCall(this).get(
                new ApiPathBuilder("/project/", projectName, "/export")
                        .accept("application/zip"),
                out);
    }

    /**
     * Import a archive file to the specified project.
     *
     * @param projectName name of the project - mandatory
     * @param archiveFile zip archive file
     * @param includeExecutions if true, import executions defined in the archive, otherwise skip them
     * @param preserveJobUuids if true, do not remove UUIDs from imported jobs, otherwise remove them
     *
     * @return Result of the import request, may contain a list of import error messages
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public ArchiveImport importArchive(final String projectName, final File archiveFile,
            final boolean includeExecutions, final boolean preserveJobUuids) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException, IOException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to import a project archive!");
        AssertUtil.notNull(archiveFile, "archiveFile is mandatory to import a project archive!"); ;
        return callImportProject(projectName, includeExecutions, preserveJobUuids,
                new ApiPathBuilder().content("application/zip", archiveFile));
    }

    private ArchiveImport callImportProject(final String projectName, final boolean includeExecutions, final boolean preserveJobUuids,
            final ApiPathBuilder param) {
        param.paths("/project/", projectName, "/import")
        .param("importExecutions", includeExecutions)
        .param("jobUuidOption", preserveJobUuids ? "preserve" : "remove");
        return new ApiCall(this).put(
                param,
                new ArchiveImportParser()
        );
    }

    /**
     * Return the configuration of a project
     *
     * @param projectName name of the project - mandatory
     *
     * @return a {@link ProjectConfig} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public ProjectConfig getProjectConfig(String projectName) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to get the config of a project !");
        return new ApiCall(this)
                .get(new ApiPathBuilder("/project/", projectName, "/config"), new ProjectConfigParser("/config"));
    }
    /**
     * Get a single project configuration key
     *
     * @param projectName name of the project - mandatory
     * @param key name of the configuration key
     *
     * @return value, or null if the value is not set
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public String getProjectConfig(final String projectName, final String key) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to get the config of a project !");
        AssertUtil.notBlank(key, "key is mandatory to get the config key value!");

        ConfigProperty configProperty = null;
        try {
            configProperty = new ApiCall(this)
                    .get(new ApiPathBuilder("/project/", projectName, "/config/", key),
                            new ProjectConfigPropertyParser("/property"));
        } catch (RundeckApiException.RundeckApiHttpStatusException e) {
            if(404==e.getStatusCode()){
                return null;
            }
            throw e;
        }
        return configProperty.getValue();
    }
    /**
     * Set a single project configuration property value
     *
     * @param projectName name of the project - mandatory
     * @param key name of the configuration property
     * @param value value of the property
     *
     * @return new value
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public String setProjectConfig(final String projectName, final String key, final String value) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to set the config of a project !");
        AssertUtil.notBlank(key, "key is mandatory to set the config key value!");
        AssertUtil.notBlank(value, "value is mandatory to set the config key value!");

        final ConfigProperty configProperty = new ApiCall(this)
                .put(new ApiPathBuilder("/project/", projectName, "/config/", key)
                        .xml(new ProjectConfigPropertyGenerator(new ConfigProperty(key, value))),
                        new ProjectConfigPropertyParser("/property"));

        return configProperty.getValue();
    }
    /**
     * Set a single project configuration property value
     *
     * @param projectName name of the project - mandatory
     * @param key name of the configuration property
     *
     * @return new value
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public void deleteProjectConfig(final String projectName, final String key) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to set the config of a project !");
        AssertUtil.notBlank(key, "key is mandatory to set the config key value!");

        new ApiCall(this).delete(new ApiPathBuilder("/project/", projectName, "/config/",
                key).accept("application/xml"));
    }
    /**
     * Return the configuration of a project
     *
     * @param projectName name of the project - mandatory
     *
     * @return a {@link ProjectConfig} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the projectName is blank (null, empty or whitespace)
     */
    public ProjectConfig setProjectConfig(String projectName, Map<String,String> configuration) throws
            RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(projectName, "projectName is mandatory to get the config of a project !");
        return new ApiCall(this)
                .put(new ApiPathBuilder("/project/", projectName, "/config")
                        .xml(new ProjectConfigGenerator(new ProjectConfig(configuration)))
                        , new ProjectConfigParser("/config"));
    }

    private Document projectDocument(String projectName, Map<String, String> configuration) {
        RundeckProject project = new RundeckProject();
        project.setName(projectName);
        if (null != configuration) {
            project.setProjectConfig(new ProjectConfig(configuration));
        }
        return new ProjectGenerator(project).generateXmlDocument();
    }

    /**
     * Store contents to a project readme.md or motd.md
     * @param projectName project name
     * @param filename filename, must be readme.md or motd.md
     * @param content content
     */
    public void storeProjectFile(final String projectName, final String filename, final String content){
        AssertUtil.notBlank(projectName, "projectName is mandatory to get the file!");
        AssertUtil.notBlank(filename, "filename is mandatory to get choose the file!");
        AssertUtil.notBlank(content, "content is mandatory to set content!");
        AssertUtil.inList("filename must be in the list: ", filename, "readme.md", "motd.md");
        new ApiCall(this)
                .put(new ApiPathBuilder("/project/", projectName, "/", filename)
                             .content( "text/plain; charset=utf-8", content)
                              .accept("text/plain"),
                     new ApiCall.PlainTextHandler()
                );
    }

    /**
     * Read contents of a project readme.md or motd.md if it exsts, or return null
     * @param projectName  project name
     * @param filename filename, must be readme.md or motd.md
     * @return contents, or null
     */
    public String readProjectFile(final String projectName, final String filename){
        AssertUtil.notBlank(projectName, "projectName is mandatory to get the readme file!");
        AssertUtil.notBlank(filename, "filename is mandatory to get choose the readme file!");
        AssertUtil.inList("filename must be in the list: ", filename, "readme.md", "motd.md");
        try {
            return new ApiCall(this)
                    .get(
                            new ApiPathBuilder("/project/", projectName, "/", filename)
                                    .accept("text/plain"),
                            new ApiCall.PlainTextHandler()
                    );
        } catch (RundeckApiException.RundeckApiHttpStatusException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Delete a project readme.md or motd.md
     * @param projectName project name
     * @param filename filename, must be readme.md or motd.md
     */
    public void deleteProjectFile(final String projectName, final String filename){
        AssertUtil.notBlank(projectName, "projectName is mandatory to get the readme file!");
        AssertUtil.notBlank(filename, "filename is mandatory to get choose the readme file!");
        AssertUtil.inList("filename must be in the list: ", filename, "readme.md", "motd.md");
        new ApiCall(this).delete(new ApiPathBuilder("/project/", projectName, "/", filename));
    }
    /*
     * Jobs
     */

    /**
     * List all jobs (for all projects)
     *
     * @return a {@link List} of {@link RundeckJob} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     */
    public List<RundeckJob> getJobs() throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException {
        List<RundeckJob> jobs = new ArrayList<RundeckJob>();
        for (RundeckProject project : getProjects()) {
            jobs.addAll(getJobs(project.getName()));
        }
        return jobs;
    }

    /**
     * List all jobs that belongs to the given project
     *
     * @param project name of the project - mandatory
     * @return a {@link List} of {@link RundeckJob} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getJobs(String, String, String, String...)
     */
    public List<RundeckJob> getJobs(String project) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return getJobs(project, null, null, new String[0]);
    }

    /**
     * List the jobs that belongs to the given project, and matches the given criteria (jobFilter, groupPath and jobIds)
     *
     * @param project name of the project - mandatory
     * @param jobFilter a filter for the job Name - optional
     * @param groupPath a group or partial group path to include all jobs within that group path - optional
     * @param jobIds a list of Job IDs to include - optional
     * @return a {@link List} of {@link RundeckJob} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getJobs(String)
     */
    public List<RundeckJob> getJobs(String project, String jobFilter, String groupPath, String... jobIds)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(project, "project is mandatory to get all jobs !");
        ApiPathBuilder builder;
        if(isApiAtLeast(Version.V14)){
            builder=new ApiPathBuilder("/project/",project,"/jobs");
        }else{
            builder=new ApiPathBuilder("/jobs").param("project", project);
        }
        return new ApiCall(this).get(
                builder
                        .param("jobFilter", jobFilter)
                        .param("groupPath", groupPath)
                        .param("idlist", StringUtils.join(jobIds, ",")),
                new ListParser<>(new JobParser(), "/jobs/job")
        );
    }

    /**
     * Export the definitions of all jobs that belongs to the given project
     *
     * @param filename path of the file where the content should be saved - mandatory
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the format or project is blank (null, empty or whitespace), or the format is
     *             invalid
     * @throws IOException if we failed to write to the file
     * @see #exportJobsToFile(String, FileType, String, String, String, String...)
     * @see #exportJobs(String, String)
     */
    public void exportJobsToFile(String filename, String format, String project) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException, IOException {
        AssertUtil.notBlank(format, "format is mandatory to export jobs !");
        exportJobsToFile(filename, FileType.valueOf(StringUtils.upperCase(format)), project);
    }

    /**
     * Export the definitions of all jobs that belongs to the given project
     *
     * @param filename path of the file where the content should be saved - mandatory
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the format is null
     * @throws IOException if we failed to write to the file
     * @see #exportJobsToFile(String, FileType, String, String, String, String...)
     * @see #exportJobs(FileType, String)
     */
    public void exportJobsToFile(String filename, FileType format, String project) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException, IOException {
        exportJobsToFile(filename, format, project, null, null, new String[0]);
    }

    /**
     * Export the definitions of the jobs that belongs to the given project, and matches the given criteria (jobFilter,
     * groupPath and jobIds)
     *
     * @param filename path of the file where the content should be saved - mandatory
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @param jobFilter a filter for the job Name - optional
     * @param groupPath a group or partial group path to include all jobs within that group path - optional
     * @param jobIds a list of Job IDs to include - optional
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the filename, format or project is blank (null, empty or whitespace), or the
     *             format is invalid
     * @throws IOException if we failed to write to the file
     * @see #exportJobsToFile(String, FileType, String, String, String, String...)
     * @see #exportJobs(FileType, String, String, String, String...)
     */
    public void exportJobsToFile(String filename, String format, String project, String jobFilter, String groupPath,
            String... jobIds) throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException,
            IllegalArgumentException, IOException {
        AssertUtil.notBlank(format, "format is mandatory to export jobs !");
        exportJobsToFile(filename,
                         FileType.valueOf(StringUtils.upperCase(format)),
                         project,
                         jobFilter,
                         groupPath,
                         jobIds);
    }

    /**
     * Export the definitions of the jobs that belongs to the given project, and matches the given criteria (jobFilter,
     * groupPath and jobIds)
     *
     * @param filename path of the file where the content should be saved - mandatory
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @param jobFilter a filter for the job Name - optional
     * @param groupPath a group or partial group path to include all jobs within that group path - optional
     * @param jobIds a list of Job IDs to include - optional
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the filename or project is blank (null, empty or whitespace), or the format
     *             is null
     * @throws IOException if we failed to write to the file
     * @see #exportJobs(FileType, String, String, String, String...)
     */
    public void exportJobsToFile(String filename, FileType format, String project, String jobFilter, String groupPath,
            String... jobIds) throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException,
            IllegalArgumentException, IOException {
        AssertUtil.notBlank(filename, "filename is mandatory to export a job !");
        InputStream inputStream = exportJobs(format, project, jobFilter, groupPath, jobIds);
        FileUtils.writeByteArrayToFile(new File(filename), IOUtils.toByteArray(inputStream));
    }

    /**
     * Export the definitions of all jobs that belongs to the given project
     *
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the format or project is blank (null, empty or whitespace), or the format is
     *             invalid
     * @see #exportJobs(FileType, String, String, String, String...)
     * @see #exportJobsToFile(String, String, String)
     */
    public InputStream exportJobs(String format, String project) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(format, "format is mandatory to export jobs !");
        return exportJobs(FileType.valueOf(StringUtils.upperCase(format)), project);
    }

    /**
     * Export the definitions of all jobs that belongs to the given project
     *
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the format is null
     * @see #exportJobs(FileType, String, String, String, String...)
     * @see #exportJobsToFile(String, FileType, String)
     */
    public InputStream exportJobs(FileType format, String project) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return exportJobs(format, project, null, null, new String[0]);
    }

    /**
     * Export the definitions of the jobs that belongs to the given project, and matches the given criteria (jobFilter,
     * groupPath and jobIds)
     *
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @param jobFilter a filter for the job Name - optional
     * @param groupPath a group or partial group path to include all jobs within that group path - optional
     * @param jobIds a list of Job IDs to include - optional
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the format or project is blank (null, empty or whitespace), or the format is
     *             invalid
     * @see #exportJobs(FileType, String, String, String, String...)
     * @see #exportJobsToFile(String, String, String, String, String, String...)
     */
    public InputStream exportJobs(String format, String project, String jobFilter, String groupPath, String... jobIds)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(format, "format is mandatory to export jobs !");
        return exportJobs(FileType.valueOf(StringUtils.upperCase(format)), project, jobFilter, groupPath, jobIds);
    }

    /**
     * Export the definitions of the jobs that belongs to the given project, and matches the given criteria (jobFilter,
     * groupPath and jobIds)
     *
     * @param format of the export. See {@link FileType} - mandatory
     * @param project name of the project - mandatory
     * @param jobFilter a filter for the job Name - optional
     * @param groupPath a group or partial group path to include all jobs within that group path - optional
     * @param jobIds a list of Job IDs to include - optional
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the format is null
     * @see #exportJobsToFile(String, FileType, String, String, String, String...)
     */
    public InputStream exportJobs(FileType format, String project, String jobFilter, String groupPath, String... jobIds)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(format, "format is mandatory to export jobs !");
        AssertUtil.notBlank(project, "project is mandatory to export jobs !");
        ApiPathBuilder builder;
        if(isApiAtLeast(Version.V14)){
            builder = new ApiPathBuilder("/project/", project, "/jobs/export");
        }else {
            builder = new ApiPathBuilder("/jobs/export").param("project", project);
        }
        return new ApiCall(this).get(builder
                .accept(format == FileType.XML ? "text/xml" : "text/yaml")
                .param("format", format)
                .param("jobFilter", jobFilter)
                .param("groupPath", groupPath)
                .param("idlist", StringUtils.join(jobIds, ",")), false);
    }

    /**
     * Export the definition of a single job (identified by the given ID)
     *
     * @param filename path of the file where the content should be saved - mandatory
     * @param format of the export. See {@link FileType} - mandatory
     * @param jobId identifier of the job - mandatory
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the filename, format or jobId is blank (null, empty or whitespace), or the
     *             format is invalid
     * @throws IOException if we failed to write to the file
     * @see #exportJobToFile(String, FileType, String)
     * @see #exportJob(String, String)
     * @see #getJob(String)
     */
    public void exportJobToFile(String filename, String format, String jobId) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException, IOException {
        AssertUtil.notBlank(format, "format is mandatory to export a job !");
        exportJobToFile(filename, FileType.valueOf(StringUtils.upperCase(format)), jobId);
    }

    /**
     * Export the definition of a single job (identified by the given ID)
     *
     * @param filename path of the file where the content should be saved - mandatory
     * @param format of the export. See {@link FileType} - mandatory
     * @param jobId identifier of the job - mandatory
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the filename or jobId is blank (null, empty or whitespace), or the format is
     *             null
     * @throws IOException if we failed to write to the file
     * @see #exportJob(FileType, String)
     * @see #getJob(String)
     */
    public void exportJobToFile(String filename, FileType format, String jobId) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException, IOException {
        AssertUtil.notBlank(filename, "filename is mandatory to export a job !");
        InputStream inputStream = exportJob(format, jobId);
        FileUtils.writeByteArrayToFile(new File(filename), IOUtils.toByteArray(inputStream));
    }

    /**
     * Export the definition of a single job, identified by the given ID
     *
     * @param format of the export. See {@link FileType} - mandatory
     * @param jobId identifier of the job - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the format or jobId is blank (null, empty or whitespace), or the format is
     *             invalid
     * @see #exportJobToFile(String, String, String)
     * @see #getJob(String)
     */
    public InputStream exportJob(String format, String jobId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(format, "format is mandatory to export a job !");
        return exportJob(FileType.valueOf(StringUtils.upperCase(format)), jobId);
    }

    /**
     * Export the definition of a single job, identified by the given ID
     *
     * @param format of the export. See {@link FileType} - mandatory
     * @param jobId identifier of the job - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace), or the format is null
     * @see #exportJobToFile(String, FileType, String)
     * @see #getJob(String)
     */
    public InputStream exportJob(FileType format, String jobId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(format, "format is mandatory to export a job !");
        AssertUtil.notBlank(jobId, "jobId is mandatory to export a job !");
        return new ApiCall(this).get(new ApiPathBuilder("/job/", jobId).param("format", format), false);
    }


    /**
     * Import the definitions of jobs, from the given input stream, using the given behavior
     *
     * @param rundeckJobsImport import request, see {@link RundeckJobsImportBuilder}
     *
     * @return a {@link RundeckJobsImportResult} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the stream or fileType is null
     * @see #importJobs(RundeckJobsImport)
     */
    public RundeckJobsImportResult importJobs(final String filename,final RundeckJobsImport rundeckJobsImport) throws RundeckApiException,
            RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException, IOException {
        AssertUtil.notBlank(filename, "filename (of jobs file) is mandatory to import jobs !");
        FileInputStream stream = null;
        try {
            stream = FileUtils.openInputStream(new File(filename));
            return importJobs(RundeckJobsImportBuilder.builder(rundeckJobsImport).setStream(stream).build());
        } finally {

            IOUtils.closeQuietly(stream);
        }
    }
    /**
     * Import the definitions of jobs, from the given input stream, using the given behavior
     *
     * @param rundeckJobsImport import request, see {@link RundeckJobsImportBuilder}
     *
     * @return a {@link RundeckJobsImportResult} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the stream or fileType is null
     * @see #importJobs(String, RundeckJobsImport)
     */
    public RundeckJobsImportResult importJobs(final RundeckJobsImport rundeckJobsImport) throws RundeckApiException,
            RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notNull(rundeckJobsImport.getStream(), "inputStream of jobs is mandatory to import jobs !");
        AssertUtil.notNull(rundeckJobsImport.getFileType(), "fileType is mandatory to import jobs !");
        ApiPathBuilder request;
        if(isApiAtLeast(Version.V14)){
            AssertUtil.notNull(rundeckJobsImport.getProject(), "project is mandatory to import jobs !");
            request = new ApiPathBuilder("/project/", rundeckJobsImport.getProject(), "/jobs/import");
        }else {
            request = new ApiPathBuilder(JOBS_IMPORT);
        }
        request.param("format", rundeckJobsImport.getFileType())
                .param("dupeOption", rundeckJobsImport.getImportMethod());

        if(isApiAtLeast(Version.V14)){
            if (null != rundeckJobsImport.getFile()) {
                request.content(rundeckJobsImport.getFileType().getContentType(), rundeckJobsImport.getFile());
            } else {
                request.content(rundeckJobsImport.getFileType().getContentType(), rundeckJobsImport.getStream());
            }
        }else{
            if (null != rundeckJobsImport.getFile()) {
                request.attach("xmlBatch", rundeckJobsImport.getFile());
            } else {
                request.attach("xmlBatch", rundeckJobsImport.getStream());
            }
        }


        if(null!=rundeckJobsImport.getUuidImportBehavior()) {
            //API v9
            request.param("uuidOption", rundeckJobsImport.getUuidImportBehavior());
        }
        if(!isApiAtLeast(Version.V14) && null!=rundeckJobsImport.getProject()) {
            //API v8
            request.param("project", rundeckJobsImport.getProject());
        }
        return new ApiCall(this).post(request, new JobsImportResultParser("result"));
    }

    /**
     * Find a job, identified by its project, group and name. Note that the groupPath is optional, as a job does not
     * need to belong to a group (either pass null, or an empty string).
     *
     * @param project name of the project - mandatory
     * @param groupPath group to which the job belongs (if it belongs to a group) - optional
     * @param name of the job to find - mandatory
     * @return a {@link RundeckJob} instance - null if not found
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project or the name is blank (null, empty or whitespace)
     * @see #getJob(String)
     */
    public RundeckJob findJob(String project, String groupPath, String name) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(project, "project is mandatory to find a job !");
        AssertUtil.notBlank(name, "job name is mandatory to find a job !");
        List<RundeckJob> jobs = getJobs(project, name, groupPath, new String[0]);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    /**
     * Get the definition of a single job, identified by the given ID
     *
     * @param jobId identifier of the job - mandatory
     * @return a {@link RundeckJob} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     * @see #findJob(String, String, String)
     * @see #exportJob(String, String)
     */
    public RundeckJob getJob(String jobId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(jobId, "jobId is mandatory to get the details of a job !");

        String path= getApiVersion() < Version.V18.getVersionNumber()
                ? "/job/"+jobId
                : "/job/"+jobId+"/info";

        return new ApiCall(this).get(new ApiPathBuilder(path), new JobParser(getApiVersion() < Version.V18.getVersionNumber()
                                                                                            ? "joblist/job"
                                                                                            : "job"
                                                                        ));
    }

    /**
     * Delete a single job, identified by the given ID
     *
     * @param jobId identifier of the job - mandatory
     * @return the success message (note that in case of error, you'll get an exception)
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public String deleteJob(String jobId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(jobId, "jobId is mandatory to delete a job !");
        new ApiCall(this).delete(new ApiPathBuilder("/job/", jobId));
        return "Job " + jobId + " was deleted successfully";
    }
    /**
     * Delete multiple jobs, identified by the given IDs
     *
     * @param jobIds List of job IDS
     * @return the bulk delete result
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckJobDeleteBulk deleteJobs(final List<String> jobIds) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        if (null == jobIds || 0 == jobIds.size()) {
            throw new IllegalArgumentException("jobIds are mandatory to delete a job");
        }
        return new ApiCall(this).post(new ApiPathBuilder("/jobs/delete").field("ids",jobIds),
                                        new BulkDeleteParser("/deleteJobs"));
    }

    /**
     * Trigger the execution of a Rundeck job (identified by the given ID), and return immediately (without waiting the
     * end of the job execution)
     *
     * @param jobRun the RunJob, see {@link RunJobBuilder}
     * @return a {@link RundeckExecution} instance for the newly created (and running) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     * @see #runJob(RunJob)
     */
    public RundeckExecution triggerJob(final RunJob jobRun)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(jobRun.getJobId(), "jobId is mandatory to trigger a job !");

        ApiPathBuilder apiPath = new ApiPathBuilder("/job/", jobRun.getJobId(), "/run");
        String options= ParametersUtil.generateArgString(jobRun.getOptions());

        if (this.getApiVersion() < Version.V13.getVersionNumber()) {
            apiPath.param("argString",options)
                    .nodeFilters(jobRun.getNodeFilters());
            if(null!=jobRun.getAsUser()) {
                apiPath.param("asUser", jobRun.getAsUser());
            }

            return new ApiCall(this).get(
                    apiPath,
                    new ExecutionParser(
                            "/executions/execution"
                    )

            );
        }else{
            apiPath.field("argString",options)
                    .nodeFilters(jobRun.getNodeFilters());
            if(null!=jobRun.getAsUser()) {
                apiPath.field("asUser", jobRun.getAsUser());
            }

            if((options == null || options.isEmpty()) && (jobRun.getAsUser()==null || jobRun.getAsUser().isEmpty()) ){
                apiPath.emptyContent();
            }

            return new ApiCall(this).post(
                    apiPath,
                    new ExecutionParser(
                            "/executions/execution"
                    )

            );
        }



    }


    /**
     * Run a Rundeck job (identified by the given ID), and wait until its execution is finished (or aborted) to return.
     * We will poll the Rundeck server at regular interval (every 5 seconds) to know if the execution is finished (or
     * aborted) or is still running.
     *
     * @param runJob the RunJob, see {@link RunJobBuilder}
     *
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     * @see #triggerJob(RunJob)
     * @see #runJob(RunJob, long, TimeUnit)
     */
    public RundeckExecution runJob(final RunJob runJob) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return runJob(runJob, DEFAULT_POOLING_INTERVAL, DEFAULT_POOLING_UNIT);
    }

    /**
     * Run a Rundeck job (identified by the given ID), and wait until its execution is finished (or aborted) to return.
     * We will poll the Rundeck server at regular interval (configured by the poolingInterval/poolingUnit couple) to
     * know if the execution is finished (or aborted) or is still running.
     *
     * @param jobRun the RunJob, see {@link RunJobBuilder}
     * @param poolingInterval for checking the status of the execution. Must be > 0.
     * @param poolingUnit     unit (seconds, milli-seconds, ...) of the interval. Default to seconds.
     *
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     * @see #triggerJob(RunJob)
     */
    public RundeckExecution runJob(final RunJob jobRun, long poolingInterval,
            TimeUnit poolingUnit) throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException,
            IllegalArgumentException {

        if (poolingInterval <= 0) {
            poolingInterval = DEFAULT_POOLING_INTERVAL;
            poolingUnit = DEFAULT_POOLING_UNIT;
        }
        if (poolingUnit == null) {
            poolingUnit = DEFAULT_POOLING_UNIT;
        }

        RundeckExecution execution = triggerJob(jobRun);
        while (ExecutionStatus.RUNNING.equals(execution.getStatus())) {
            try {
                Thread.sleep(poolingUnit.toMillis(poolingInterval));
            } catch (InterruptedException e) {
                break;
            }
            execution = getExecution(execution.getId());
        }
        return execution;
    }

    /*
     * Ad-hoc commands
     */


    /**
     * Trigger the execution of an ad-hoc command, and return immediately (without waiting the end of the execution).
     * The command will be dispatched to nodes, accordingly to the nodeFilters parameter.
     *
     * @param command the RunAdhocCommand. Project and command are mandatory, see {@link RunAdhocCommandBuilder}
     * @return a {@link RundeckExecution} instance for the newly created (and running) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project or command is blank (null, empty or whitespace)
     * @see #runAdhocCommand(RunAdhocCommand)
     */
    public RundeckExecution triggerAdhocCommand(RunAdhocCommand command) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(command.getProject(), "project is mandatory to trigger an ad-hoc command !");
        AssertUtil.notBlank(command.getCommand(), "command is mandatory to trigger an ad-hoc command !");
        ApiPathBuilder apiPath;
        if(isApiAtLeast(Version.V14)){
            apiPath = new ApiPathBuilder("/project/", command.getProject(), "/run/command");
        }else{
            apiPath = new ApiPathBuilder("/run/command").param("project", command.getProject());
        }
        apiPath.param("exec", command.getCommand())
               .param("nodeThreadcount", command.getNodeThreadcount())
               .param("nodeKeepgoing", command.getNodeKeepgoing())
               .nodeFilters(command.getNodeFilters());
        if (null != command.getAsUser()) {
            apiPath.param("asUser", command.getAsUser());
        }
        RundeckExecution execution = new ApiCall(this).get(apiPath, new ExecutionParser("/execution"));
        // the first call just returns the ID of the execution, so we need another call to get a "real" execution
        return getExecution(execution.getId());
    }


    /**
     * Run an ad-hoc command, and wait until its execution is finished (or aborted) to return. We will poll the Rundeck
     * server at regular interval (every 5 seconds) to know if the execution is finished (or aborted) or is still
     * running. The command will be dispatched to nodes, accordingly to the nodeFilters parameter.
     *
     * @param command the RunAdhocCommand, see {@link RunAdhocCommandBuilder}
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project or command is blank (null, empty or whitespace)
     * @see #runAdhocCommand(RunAdhocCommand, long, TimeUnit)
     * @see #triggerAdhocCommand(RunAdhocCommand)
     */
    public RundeckExecution runAdhocCommand(RunAdhocCommand command) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return runAdhocCommand(command,
                               DEFAULT_POOLING_INTERVAL,
                               DEFAULT_POOLING_UNIT);
    }

    /**
     * Run an ad-hoc command, and wait until its execution is finished (or aborted) to return. We will poll the Rundeck
     * server at regular interval (configured by the poolingInterval/poolingUnit couple) to know if the execution is
     * finished (or aborted) or is still running. The command will be dispatched to nodes, accordingly to the
     * nodeFilters parameter.
     *
     * @param command the RunAdhocCommand, see {@link RunAdhocCommandBuilder}
     * @param poolingInterval for checking the status of the execution. Must be > 0.
     * @param poolingUnit unit (seconds, milli-seconds, ...) of the interval. Default to seconds.
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project or command is blank (null, empty or whitespace)
     * @see #triggerAdhocCommand(RunAdhocCommand)
     */
    public RundeckExecution runAdhocCommand(RunAdhocCommand command, long poolingInterval, TimeUnit poolingUnit)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        if (poolingInterval <= 0) {
            poolingInterval = DEFAULT_POOLING_INTERVAL;
            poolingUnit = DEFAULT_POOLING_UNIT;
        }
        if (poolingUnit == null) {
            poolingUnit = DEFAULT_POOLING_UNIT;
        }

        RundeckExecution execution = triggerAdhocCommand(command);
        while (ExecutionStatus.RUNNING.equals(execution.getStatus())) {
            try {
                Thread.sleep(poolingUnit.toMillis(poolingInterval));
            } catch (InterruptedException e) {
                break;
            }
            execution = getExecution(execution.getId());
        }
        return execution;
    }

    /*
     * Ad-hoc scripts
     */


    /**
     * Trigger the execution of an ad-hoc script read from a file, and return immediately (without waiting the end of
     * the execution). The script will be dispatched to nodes, accordingly to the nodeFilters parameter.
     *
     * @param script         the RunAdhocScript, see {@link RunAdhocScriptBuilder}
     * @param scriptFilename a file to read as the input script stream
     *
     * @return a {@link RundeckExecution} instance for the newly created (and running) execution - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the script is null
     * @throws IOException              if an error occurs reading the script file
     * @see #triggerAdhocScript(RunAdhocScript)
     * @see #runAdhocScript(RunAdhocScript, long, java.util.concurrent.TimeUnit)
     */
    public RundeckExecution triggerAdhocScript(final RunAdhocScript script, final String scriptFilename) throws
            RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException, IOException {
        AssertUtil.notBlank(scriptFilename, "scriptFilename is mandatory to trigger an ad-hoc script !");
        FileInputStream stream = null;
        try {
            stream = FileUtils.openInputStream(new File(scriptFilename));
            return triggerAdhocScript(RunAdhocScriptBuilder.builder(script)
                    .setScript(stream)
                    .build());
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }
    /**
     * Trigger the execution of an ad-hoc script, and return immediately (without waiting the end of the execution). The
     * script will be dispatched to nodes, accordingly to the nodeFilters parameter.
     *
     * @param script the RunAdhocScript, see {@link RunAdhocScriptBuilder}
     * @return a {@link RundeckExecution} instance for the newly created (and running) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the script is null
     * @see #triggerAdhocScript(RunAdhocScript, String)
     * @see #runAdhocScript(RunAdhocScript)
     */
    public RundeckExecution triggerAdhocScript(RunAdhocScript script) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(script.getProject(), "project is mandatory to trigger an ad-hoc script !");
        AssertUtil.notNull(script.getScript(), "script is mandatory to trigger an ad-hoc script !");
        ApiPathBuilder apiPath ;
        if(isApiAtLeast(Version.V14)){
            apiPath = new ApiPathBuilder("/project/", script.getProject(), "/run/script");
        }else{
            apiPath = new ApiPathBuilder("/run/script").param("project", script.getProject());
        }
        apiPath.attach("scriptFile", script.getScript())
               .param("argString", script.getArgString())
               .param("nodeThreadcount", script.getNodeThreadcount())
               .param("nodeKeepgoing", script.getNodeKeepgoing())
               .param("scriptInterpreter", script.getScriptInterpreter())
               .param("interpreterArgsQuoted", script.getInterpreterArgsQuoted())
               .nodeFilters(script.getNodeFilters());
        if (null != script.getAsUser()) {
            apiPath.param("asUser", script.getAsUser());
        }
        RundeckExecution execution = new ApiCall(this).post(apiPath, new ExecutionParser("/execution"));
        // the first call just returns the ID of the execution, so we need another call to get a "real" execution
        return getExecution(execution.getId());
    }


    /**
     * Run an ad-hoc script, and wait until its execution is finished (or aborted) to return. We will poll the Rundeck
     * server at regular interval (every 5 seconds) to know if the execution is finished (or aborted) or is still
     * running. The script will be dispatched to nodes, accordingly to the nodeFilters parameter.
     *
     * @param script the RunAdhocScript, see {@link RunAdhocScriptBuilder}
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the script is null
     * @throws IOException if we failed to read the file
     * @see #runAdhocScript(RunAdhocScript, long, java.util.concurrent.TimeUnit)
     * @see #triggerAdhocScript(RunAdhocScript)
     */
    public RundeckExecution runAdhocScript(RunAdhocScript script) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException, IOException {
        return runAdhocScript(script,
                              DEFAULT_POOLING_INTERVAL,
                              DEFAULT_POOLING_UNIT);
    }

    /**
     * Run an ad-hoc script read from a file, and wait until its execution is finished (or aborted) to return. We will
     * poll the Rundeck server at regular interval (configured by the poolingInterval/poolingUnit couple) to know if the
     * execution is finished (or aborted) or is still running. The script will be dispatched to nodes, accordingly to
     * the nodeFilters parameter.
     *
     * @param script          the RunAdhocScript, see {@link RunAdhocScriptBuilder}
     * @param scriptFilename  filename of a script to read
     * @param poolingInterval for checking the status of the execution. Must be > 0.
     * @param poolingUnit     unit (seconds, milli-seconds, ...) of the interval. Default to seconds.
     *
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the script is null
     * @throws IOException              if we failed to read the file
     * @see #runAdhocScript(RunAdhocScript)
     * @see #triggerAdhocScript(RunAdhocScript, String)
     */
    public RundeckExecution runAdhocScript(final RunAdhocScript script, final String scriptFilename,
            final long poolingInterval, final TimeUnit poolingUnit) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException,
            IllegalArgumentException, IOException {
        FileInputStream stream = null;
        try {
            stream = FileUtils.openInputStream(new File(scriptFilename));
            return runAdhocScript(RunAdhocScriptBuilder.builder(script)
                    .setScript(stream)
                    .build(), poolingInterval, poolingUnit);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }
    /**
     * Run an ad-hoc script, and wait until its execution is finished (or aborted) to return. We will poll the Rundeck
     * server at regular interval (configured by the poolingInterval/poolingUnit couple) to know if the execution is
     * finished (or aborted) or is still running. The script will be dispatched to nodes, accordingly to the nodeFilters
     * parameter.
     *
     * @param script the RunAdhocScript, see {@link RunAdhocScriptBuilder}
     * @param poolingInterval for checking the status of the execution. Must be > 0.
     * @param poolingUnit unit (seconds, milli-seconds, ...) of the interval. Default to seconds.
     * @return a {@link RundeckExecution} instance for the (finished/aborted) execution - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace) or the script is null
     * @throws IOException if we failed to read the file
     * @see #runAdhocScript(RunAdhocScript, long, TimeUnit)
     * @see #triggerAdhocScript(RunAdhocScript)
     */
    public RundeckExecution runAdhocScript(final RunAdhocScript script, long poolingInterval,
            TimeUnit poolingUnit) throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException,
            IllegalArgumentException {
        if (poolingInterval <= 0) {
            poolingInterval = DEFAULT_POOLING_INTERVAL;
            poolingUnit = DEFAULT_POOLING_UNIT;
        }
        if (poolingUnit == null) {
            poolingUnit = DEFAULT_POOLING_UNIT;
        }

        RundeckExecution execution = triggerAdhocScript(script);
        while (ExecutionStatus.RUNNING.equals(execution.getStatus())) {
            try {
                Thread.sleep(poolingUnit.toMillis(poolingInterval));
            } catch (InterruptedException e) {
                break;
            }
            execution = getExecution(execution.getId());
        }
        return execution;
    }

    /*
     * Executions
     */

    /**
     * Get all running executions (for all projects)
     *
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @see #getRunningExecutions(String)
     */
    public List<RundeckExecution> getRunningExecutions() throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException {
        if (this.getApiVersion() >= Version.V9.getVersionNumber()) {
            //simply query using '*'
            return getRunningExecutions("*");
        } else {
            List<RundeckExecution> executions = new ArrayList<RundeckExecution>();
            for (RundeckProject project : getProjects()) {
                executions.addAll(getRunningExecutions(project.getName()));
            }
            return executions;
        }
    }

    /**
     * Get the running executions for the given project
     *
     * @param project name of the project - mandatory
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getRunningExecutions()
     */
    public List<RundeckExecution> getRunningExecutions(String project) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(project, "project is mandatory get all running executions !");
        ApiPathBuilder builder;
        if (isApiAtLeast(Version.V14)) {
            builder = new ApiPathBuilder("/project/", project, "/executions/running");
        } else {
            builder = new ApiPathBuilder("/executions/running").param("project", project);
        }
        return new ApiCall(this).get(builder, new ListParser<>(new ExecutionParser(), "/executions/execution"));
    }

    /**
     * Get the executions of the given job
     *
     * @param jobId identifier of the job - mandatory
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     * @see #getJobExecutions(String, RundeckExecution.ExecutionStatus, Long, Long)
     */
    public List<RundeckExecution> getJobExecutions(String jobId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return getJobExecutions(jobId, (ExecutionStatus) null);
    }

    /**
     * Get the executions of the given job
     *
     * @param jobId identifier of the job - mandatory
     * @param status of the executions, see {@link ExecutionStatus} - optional (null for all)
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace), or the executionStatus is
     *             invalid
     * @see #getJobExecutions(String, String, Long, Long)
     */
    public List<RundeckExecution> getJobExecutions(String jobId, String status) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getJobExecutions(jobId,
                                StringUtils.isBlank(status) ? null : ExecutionStatus.valueOf(StringUtils.upperCase(status)));
    }

    /**
     * Get the executions of the given job
     *
     * @param jobId identifier of the job - mandatory
     * @param status of the executions, see {@link ExecutionStatus} - optional (null for all)
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     * @see #getJobExecutions(String, RundeckExecution.ExecutionStatus, Long, Long)
     */
    public List<RundeckExecution> getJobExecutions(String jobId, ExecutionStatus status) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getJobExecutions(jobId, status, null, null);
    }

    /**
     * Get the executions of the given job
     *
     * @param jobId identifier of the job - mandatory
     * @param status of the executions, see {@link ExecutionStatus} - optional (null for all)
     * @param max number of results to return - optional (null for all)
     * @param offset the 0-indexed offset for the first result to return - optional
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace), or the executionStatus is
     *             invalid
     * @see #getJobExecutions(String, RundeckExecution.ExecutionStatus, Long, Long)
     */
    public List<RundeckExecution> getJobExecutions(String jobId, String status, Long max, Long offset)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getJobExecutions(jobId,
                                StringUtils.isBlank(status) ? null : ExecutionStatus.valueOf(StringUtils.upperCase(status)),
                                max,
                                offset);
    }

    /**
     * Get the executions of the given job
     *
     * @param jobId identifier of the job - mandatory
     * @param status of the executions, see {@link ExecutionStatus} - optional (null for all)
     * @param max number of results to return - optional (null for all)
     * @param offset the 0-indexed offset for the first result to return - optional
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public List<RundeckExecution> getJobExecutions(String jobId, ExecutionStatus status, Long max, Long offset)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(jobId, "jobId is mandatory to get the executions of a job !");
        return new ApiCall(this).get(new ApiPathBuilder("/job/", jobId, "/executions").param("status", status)
                                         .param("max", max)
                                         .param("offset", offset),
                                     new ListParser<RundeckExecution>(new ExecutionParser(),
                                                                      "/executions/execution"));
    }

    /**
     * Get executions based on query parameters
     *
     * @param query query parameters for the request
     * @param max number of results to return - optional (null for all)
     * @param offset the 0-indexed offset for the first result to return - optional
     * @return a {@link List} of {@link RundeckExecution} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public PagedResults<RundeckExecution> getExecutions(ExecutionQuery query, Long max, Long offset)
        throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(query.getProject(), "project is required for execution query");
        ApiPathBuilder builder ;
        if (isApiAtLeast(Version.V14)) {
            builder = new ApiPathBuilder("/project/",query.getProject(),"/executions");
        } else {
            if (!query.notBlank()) {
                throw new IllegalArgumentException("Some execution query parameter must be set");
            }
            builder = new ApiPathBuilder("/executions");
        }

        return new ApiCall(this).get(
                builder.param(new ExecutionQueryParameters(query))
                       .param("max", max)
                       .param("offset", offset),
                new PagedResultParser<>(new ListParser<>(new ExecutionParser(), "execution"), "/executions")
        );
    }

    /**
     * Get a single execution, identified by the given ID
     *
     * @param executionId identifier of the execution - mandatory
     * @return a {@link RundeckExecution} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent execution with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the executionId is null
     */
    public RundeckExecution getExecution(Long executionId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the details of an execution !");
        return new ApiCall(this).get(
                new ApiPathBuilder("/execution/", executionId.toString()),
                new ExecutionParser("/executions/execution")
        );
    }

    /**
     * Abort an execution (identified by the given ID). The execution should be running...
     *
     * @param executionId identifier of the execution - mandatory
     * @return a {@link RundeckAbort} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent execution with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the executionId is null
     */
    public RundeckAbort abortExecution(Long executionId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return abortExecution(executionId, null);
    }
    /**
     * Abort an execution (identified by the given ID). The execution should be running...
     *
     * @param executionId identifier of the execution - mandatory
     * @param asUser specify a user name to abort the job as, must have 'killAs' permission
     * @return a {@link RundeckAbort} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent execution with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the executionId is null
     */
    public RundeckAbort abortExecution(Long executionId, final String asUser) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to abort an execution !");
        ApiPathBuilder apiPath = new ApiPathBuilder("/execution/", executionId.toString(), "/abort");
        if(null!=asUser) {
            apiPath.param("asUser", asUser);
        }
        return new ApiCall(this).get(apiPath, new AbortParser( "/abort"));
    }

    /**
     * Delete all executions for a job specified by a job ID
     *
     * @param jobId Identifier for the job
     *
     * @return a {@link DeleteExecutionsResponse} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent
     *                                  execution with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based
     *                                  authentication)
     * @throws IllegalArgumentException if the executionIds is null
     */
    public DeleteExecutionsResponse deleteAllJobExecutions(final String jobId)
            throws RundeckApiException, RundeckApiLoginException,
                   RundeckApiTokenException, IllegalArgumentException
    {
        AssertUtil.notNull(jobId, "jobId is mandatory to delete executions!");
        return new ApiCall(this).delete(
                new ApiPathBuilder("/job/",jobId,"/executions"),
                new DeleteExecutionsResponseParser( "/deleteExecutions")
        );
    }

    /**
     * Delete a set of executions, identified by the given IDs
     *
     * @param executionIds set of identifiers for the executions - mandatory
     * @return a {@link DeleteExecutionsResponse} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent execution with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the executionIds is null
     */
    public DeleteExecutionsResponse deleteExecutions(final Set<Long> executionIds)
            throws RundeckApiException, RundeckApiLoginException,
                   RundeckApiTokenException, IllegalArgumentException
    {
        AssertUtil.notNull(executionIds, "executionIds is mandatory to delete executions!");
        if (executionIds.size() < 1) {
            throw new IllegalArgumentException("executionIds cannot be empty");
        }
        final ApiPathBuilder apiPath = new ApiPathBuilder("/executions/delete").xml(
                new DeleteExecutionsGenerator(executionIds)
        );
        return new ApiCall(this).post(
                apiPath,
                new DeleteExecutionsResponseParser(  "/deleteExecutions")
        );
    }

    /**
     * Delete a single execution, identified by the given ID
     *
     * @param executionId identifier for the execution - mandatory
     * @throws RundeckApiException in case of error when calling the API (non-existent execution with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the executionId is null
     */
    public void deleteExecution(final Long executionId)
            throws RundeckApiException, RundeckApiLoginException,
                   RundeckApiTokenException, IllegalArgumentException
    {
        AssertUtil.notNull(executionId, "executionId is mandatory to delete an execution!");
        new ApiCall(this).delete(new ApiPathBuilder("/execution/", executionId.toString()));
    }

    /*
     * History
     */

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, null, null,(String) null, (String) null, null, null, null, null);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param max number of results to return - optional (default to 20)
     * @param offset the 0-indexed offset for the first result to return - optional (default to O)
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, Long max, Long offset) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, null, null, (String)null, (String)null, null, null, max, offset);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param jobId include only events matching the given job ID - optional
     * @param reportId include only events matching the given report ID - optional
     * @param user include only events created by the given user - optional
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, String jobId, String reportId, String user)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, jobId, reportId, user, null, null, null, null, null);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param jobId include only events matching the given job ID - optional
     * @param reportId include only events matching the given report ID - optional
     * @param user include only events created by the given user - optional
     * @param max number of results to return - optional (default to 20)
     * @param offset the 0-indexed offset for the first result to return - optional (default to O)
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, String jobId, String reportId, String user, Long max, Long offset)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, jobId, reportId, user, null, null, null, max, offset);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param recent include only events matching the given period of time. Format : "XY", where X is an integer, and Y
     *            is one of : "h" (hour), "d" (day), "w" (week), "m" (month), "y" (year). Example : "2w" (= last 2
     *            weeks), "5d" (= last 5 days), etc. Optional.
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, String recent) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, null, null, null, recent, null, null, null, null);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param recent include only events matching the given period of time. Format : "XY", where X is an integer, and Y
     *            is one of : "h" (hour), "d" (day), "w" (week), "m" (month), "y" (year). Example : "2w" (= last 2
     *            weeks), "5d" (= last 5 days), etc. Optional.
     * @param max number of results to return - optional (default to 20)
     * @param offset the 0-indexed offset for the first result to return - optional (default to O)
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, String recent, Long max, Long offset) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, null, null, null, recent, null, null, max, offset);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param begin date for the earlier events to retrieve - optional
     * @param end date for the latest events to retrieve - optional
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, Date begin, Date end) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, null, null, (String)null, (String)null, begin, end, null, null);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param begin date for the earlier events to retrieve - optional
     * @param end date for the latest events to retrieve - optional
     * @param max number of results to return - optional (default to 20)
     * @param offset the 0-indexed offset for the first result to return - optional (default to O)
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getHistory(String, String, String, String, String, Date, Date, Long, Long)
     */
    public RundeckHistory getHistory(String project, Date begin, Date end, Long max, Long offset)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        return getHistory(project, null, null, (String)null, (String) null, begin, end, max, offset);
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project name of the project - mandatory
     * @param jobId include only events matching the given job ID - optional
     * @param reportId include only events matching the given report ID - optional
     * @param user include only events created by the given user - optional
     * @param recent include only events matching the given period of time. Format : "XY", where X is an integer, and Y
     *            is one of : "h" (hour), "d" (day), "w" (week), "m" (month), "y" (year). Example : "2w" (= last 2
     *            weeks), "5d" (= last 5 days), etc. Optional.
     * @param begin date for the earlier events to retrieve - optional
     * @param end date for the latest events to retrieve - optional
     * @param max number of results to return - optional (default to 20)
     * @param offset the 0-indexed offset for the first result to return - optional (default to O)
     * @return a {@link RundeckHistory} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     */
    public RundeckHistory getHistory(String project, String jobId, String reportId, String user, String recent,
            Date begin, Date end, Long max, Long offset) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(project, "project is mandatory to get the history !");
        ApiPathBuilder builder;
        if(isApiAtLeast(Version.V14)){
            builder = new ApiPathBuilder("/project/",project,"/history");
        }else {
            builder = new ApiPathBuilder("/history").param("project", project);
        }
        return new ApiCall(this).get(
                builder.param("jobIdFilter", jobId)
                       .param("reportIdFilter", reportId)
                       .param("userFilter", user)
                       .param("recentFilter", recent)
                       .param("begin", begin)
                       .param("end", end)
                       .param("max", max)
                       .param("offset", offset),
                new HistoryParser("/events")
        );
    }

    /**
     * Get the (events) history for the given project
     *
     * @param project         name of the project - mandatory
     * @param includeJobNames list of job names ("group/name") to include results for
     * @param excludeJobNames list of job names ("group/name") to exclude results for
     * @param user            include only events created by the given user - optional
     * @param recent          include only events matching the given period of time. Format : "XY", where X is an
     *                        integer, and Y is one of : "h" (hour), "d" (day), "w" (week), "m" (month), "y" (year).
     *                        Example : "2w" (= last 2 weeks), "5d" (= last 5 days), etc. Optional.
     * @param begin           date for the earlier events to retrieve - optional
     * @param end             date for the latest events to retrieve - optional
     * @param max             number of results to return - optional (default to 20)
     * @param offset          the 0-indexed offset for the first result to return - optional (default to O)
     *
     * @return a {@link RundeckHistory} instance - won't be null
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     */
    public RundeckHistory getHistory(String project,
                                     String user,
                                     String recent,
                                     List<String> includeJobNames,
                                     List<String> excludeJobNames,
                                     Date begin,
                                     Date end,
                                     Long max,
                                     Long offset)
        throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {

        AssertUtil.notBlank(project, "project is mandatory to get the history !");
        final ApiPathBuilder builder = new ApiPathBuilder("/history").param("project", project)
            .field("jobListFilter", includeJobNames)
            .field("excludeJobListFilter", excludeJobNames)
            .param("userFilter", user)
            .param("recentFilter", recent)
            .param("begin", begin)
            .param("end", end)
            .param("max", max)
            .param("offset", offset);

        return new ApiCall(this).postOrGet(builder, new HistoryParser("/events"));
    }

    /*
     * Nodes
     */

    /**
     * List all nodes (for all projects)
     *
     * @return a {@link List} of {@link RundeckNode} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     */
    public List<RundeckNode> getNodes() throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException {
        List<RundeckNode> nodes = new ArrayList<RundeckNode>();
        for (RundeckProject project : getProjects()) {
            nodes.addAll(getNodes(project.getName()));
        }
        return nodes;
    }

    /**
     * List all nodes that belongs to the given project
     *
     * @param project name of the project - mandatory
     * @return a {@link List} of {@link RundeckNode} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     * @see #getNodes(String, Properties)
     */
    public List<RundeckNode> getNodes(String project) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        return getNodes(project, null);
    }

    /**
     * List nodes that belongs to the given project
     *
     * @param project name of the project - mandatory
     * @param nodeFilters for filtering the nodes - optional. See {@link NodeFiltersBuilder}
     * @return a {@link List} of {@link RundeckNode} : might be empty, but won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the project is blank (null, empty or whitespace)
     */
    public List<RundeckNode> getNodes(String project, Properties nodeFilters) throws RundeckApiException,
            RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(project, "project is mandatory to get all nodes !");
        ApiPathBuilder builder ;
        if(isApiAtLeast(Version.V14)){
            builder = new ApiPathBuilder("/project/", project, "/resources");
        }else{
            builder = new ApiPathBuilder("/resources").param("project", project);
        }
        return new ApiCall(this).get(
                builder.nodeFilters(nodeFilters),
                new ListParser<>(new NodeParser(), "project/node")
        );
    }

    /**
     * Get the definition of a single node
     *
     * @param name of the node - mandatory
     * @param project name of the project - mandatory
     * @return a {@link RundeckNode} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent name or project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the name or project is blank (null, empty or whitespace)
     */
    public RundeckNode getNode(String name, String project) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(name, "the name of the node is mandatory to get a node !");
        AssertUtil.notBlank(project, "project is mandatory to get a node !");
        ApiPathBuilder builder;
        if(isApiAtLeast(Version.V14)){
            builder = new ApiPathBuilder("/project/", project, "/resource/", name);
        }else{
            builder = new ApiPathBuilder("/resource/", name).param("project", project);
        }

        return new ApiCall(this).get(builder, new NodeParser("project/node"));
    }

    /**
     * Get the output of a job execution
     *
     * @param executionId id of the execution - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent name or project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the name or project is blank (null, empty or whitespace)
     */
    public InputStream getOutput(String executionId) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(executionId, "the execution id is mandatory to get execution output !");
        return new ApiCall(this).getNonApi(new ApiPathBuilder("/execution/downloadOutput/", executionId));
    }

    /**
     * Get the html page of the user's profile
     *
     * @param username - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent name or project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the name or project is blank (null, empty or whitespace)
     */
    public InputStream getProfilePage(String username) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(username, "the username is mandatory to get profile page !");
        return new ApiCall(this).getNonApi(new ApiPathBuilder("/user/profile?login=", username));
    }


    /**
     * Generate a new token and get the result page (which is the html page of the user's profile)
     *
     * @param username - mandatory
     * @return an {@link InputStream} instance, not linked to any network resources - won't be null
     * @throws RundeckApiException in case of error when calling the API (non-existent name or project with this name)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the name or project is blank (null, empty or whitespace)
     */
    public InputStream generateToken(String username) throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notBlank(username, "the username is mandatory to generate the token");
        return new ApiCall(this).getNonApi(new ApiPathBuilder("/user/generateApiToken?login=", username));
    }


    /**
     * Get the execution output of the given job
     *
     * @param executionId identifier of the execution - mandatory
     * @param offset byte offset to read from in the file. 0 indicates the beginning.
     * @param lastlines nnumber of lines to retrieve from the end of the available output. If specified it will override the offset value and return only the specified number of lines at the end of the log.
     * @param lastmod epoch datestamp in milliseconds, return results only if modification changed since the specified date OR if more data is available at the given offset
     * @param maxlines maximum number of lines to retrieve forward from the specified offset.
     * @return {@link RundeckOutput}
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckOutput getExecutionOutput(Long executionId, int offset, int lastlines, long lastmod, int maxlines)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the output of a job execution!");
        ApiPathBuilder param = new ApiPathBuilder(
                "/execution/", executionId.toString(),
                "/output")
                .param("offset", offset);
        if (lastlines > 0) {
            param.param("lastlines", lastlines);
        }
        if (lastmod >= 0) {
            param.param("lastmod", lastmod);
        }
        if (maxlines > 0) {
            param.param("maxlines", maxlines);
        }
        return new ApiCall(this).get(param,
                new OutputParser("/output", createOutputEntryParser()));
    }
    /**
     * Get the execution state of the given execution
     *
     * @param executionId identifier of the execution - mandatory
     * @return {@link RundeckExecutionState} the execution state
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckExecutionState getExecutionState(Long executionId)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the state of an execution!");
        ApiPathBuilder param = new ApiPathBuilder(
                "/execution/", executionId.toString(),
                "/state");

        return new ApiCall(this).get(param, new ExecutionStateParser("/executionState"));
    }

    /**
     * Get the execution output of the given execution on the specified node
     *
     * @param executionId identifier of the execution - mandatory
     * @param nodeName    name of the node
     * @param offset      byte offset to read from in the file. 0 indicates the beginning.
     * @param lastlines   nnumber of lines to retrieve from the end of the available output. If specified it will
     *                    override the offset value and return only the specified number of lines at the end of the
     *                    log.
     * @param lastmod     epoch datestamp in milliseconds, return results only if modification changed since the
     *                    specified date OR if more data is available at the given offset
     * @param maxlines    maximum number of lines to retrieve forward from the specified offset.
     *
     * @return {@link RundeckOutput}
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckOutput getExecutionOutputForNode(Long executionId, String nodeName, int offset, int lastlines,
            long lastmod, int maxlines)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the output of a job execution!");
        AssertUtil.notNull(nodeName, "nodeName is mandatory to get the output of a job execution!");
        ApiPathBuilder param = new ApiPathBuilder(
                "/execution/", executionId.toString(),
                "/output/node/", nodeName)
                .param("offset", offset);
        if(lastlines>0) {
            param.param("lastlines", lastlines);
        }
        if(lastmod>=0) {
            param.param("lastmod", lastmod);
        }
        if(maxlines>0) {
            param.param("maxlines", maxlines);
        }
        return new ApiCall(this).get(param,
                new OutputParser("/output", createOutputEntryParser()));
    }
    /**
     * Get the execution output of the given execution for the specified step
     *
     * @param executionId identifier of the execution - mandatory
     * @param stepCtx     identifier for the step
     * @param offset      byte offset to read from in the file. 0 indicates the beginning.
     * @param lastlines   nnumber of lines to retrieve from the end of the available output. If specified it will
     *                    override the offset value and return only the specified number of lines at the end of the
     *                    log.
     * @param lastmod     epoch datestamp in milliseconds, return results only if modification changed since the
     *                    specified date OR if more data is available at the given offset
     * @param maxlines    maximum number of lines to retrieve forward from the specified offset.
     *
     * @return {@link RundeckOutput}
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckOutput getExecutionOutputForStep(Long executionId, String stepCtx, int offset, int lastlines,
            long lastmod, int maxlines)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the output of a job execution!");
        AssertUtil.notNull(stepCtx, "stepCtx is mandatory to get the output of a job execution!");
        ApiPathBuilder param = new ApiPathBuilder(
                "/execution/", executionId.toString(),
                "/output/step/", stepCtx)
                .param("offset", offset);
        if (lastlines > 0) {
            param.param("lastlines", lastlines);
        }
        if (lastmod >= 0) {
            param.param("lastmod", lastmod);
        }
        if (maxlines > 0) {
            param.param("maxlines", maxlines);
        }
        return new ApiCall(this).get(param,
                new OutputParser("/output", createOutputEntryParser()));
    }
    /**
     * Get the execution output of the given execution for the specified step
     *
     * @param executionId identifier of the execution - mandatory
     * @param stepCtx     identifier for the step
     * @param offset      byte offset to read from in the file. 0 indicates the beginning.
     * @param lastlines   nnumber of lines to retrieve from the end of the available output. If specified it will
     *                    override the offset value and return only the specified number of lines at the end of the
     *                    log.
     * @param lastmod     epoch datestamp in milliseconds, return results only if modification changed since the
     *                    specified date OR if more data is available at the given offset
     * @param maxlines    maximum number of lines to retrieve forward from the specified offset.
     *
     * @return {@link RundeckOutput}
     *
     * @throws RundeckApiException      in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckOutput getExecutionOutputForNodeAndStep(Long executionId, String nodeName, String stepCtx,
            int offset, int lastlines,
            long lastmod, int maxlines)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the output of a job execution!");
        AssertUtil.notNull(nodeName, "nodeName is mandatory to get the output of a job execution!");
        AssertUtil.notNull(stepCtx, "stepCtx is mandatory to get the output of a job execution!");
        ApiPathBuilder param = new ApiPathBuilder(
                "/execution/", executionId.toString(),
                "/output/node/", nodeName,
                "/step/", stepCtx)
                .param("offset", offset);
        if (lastlines > 0) {
            param.param("lastlines", lastlines);
        }
        if (lastmod >= 0) {
            param.param("lastmod", lastmod);
        }
        if (maxlines > 0) {
            param.param("maxlines", maxlines);
        }
        return new ApiCall(this).get(param,
                new OutputParser("/output", createOutputEntryParser()));
    }


    /**
     * Get the execution output of the given job
     *
     * @param executionId identifier of the execution - mandatory
     * @param offset byte offset to read from in the file. 0 indicates the beginning.
     * @param lastmod epoch datestamp in milliseconds, return results only if modification changed since the specified date OR if more data is available at the given offset
     * @param maxlines maximum number of lines to retrieve forward from the specified offset.
     * @return {@link RundeckOutput}
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckOutput getExecutionOutput(Long executionId, int offset, long lastmod, int maxlines)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the output of a job execution!");
        ApiPathBuilder param = new ApiPathBuilder("/execution/", executionId.toString(), "/output")
                .param("offset", offset);
        if (lastmod >= 0) {
            param.param("lastmod", lastmod);
        }
        if (maxlines > 0) {
            param.param("maxlines", maxlines);
        }
        return new ApiCall(this).get(param, new OutputParser("/output", createOutputEntryParser()));
    }
    /**
     * Get the execution state output sequence of the given job
     *
     * @param executionId identifier of the execution - mandatory
     * @param stateOnly if true, include only state change output entries, otherwise include state and log entries
     * @param offset byte offset to read from in the file. 0 indicates the beginning.
     * @param lastmod epoch datestamp in milliseconds, return results only if modification changed since the specified date OR if more data is available at the given offset
     * @param maxlines maximum number of lines to retrieve forward from the specified offset.
     * @return {@link RundeckOutput}
     * @throws RundeckApiException in case of error when calling the API (non-existent job with this ID)
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     * @throws IllegalArgumentException if the jobId is blank (null, empty or whitespace)
     */
    public RundeckOutput getExecutionOutputState(Long executionId, boolean stateOnly, int offset, long lastmod,
            int maxlines)
            throws RundeckApiException, RundeckApiLoginException, RundeckApiTokenException, IllegalArgumentException {
        AssertUtil.notNull(executionId, "executionId is mandatory to get the output of a job execution!");
        ApiPathBuilder param = new ApiPathBuilder("/execution/", executionId.toString(), "/output/state")
                .param("offset", offset);
        if (lastmod >= 0) {
            param.param("lastmod", lastmod);
        }
        if (maxlines > 0) {
            param.param("maxlines", maxlines);
        }
        if(stateOnly) {
            param.param("stateOnly", true);
        }
        return new ApiCall(this).get(param, new OutputParser("/output", createOutputEntryParser()));
    }

    private OutputEntryParser createOutputEntryParser() {
        if (getApiVersion() <= Version.V5.versionNumber) {
            return new OutputEntryParserV5();
        }else{
            return new OutputEntryParser();
        }
    }


    /*
     * System Info
     */

    /**
     * Get system informations about the Rundeck server
     *
     * @return a {@link RundeckSystemInfo} instance - won't be null
     * @throws RundeckApiException in case of error when calling the API
     * @throws RundeckApiLoginException if the login fails (in case of login-based authentication)
     * @throws RundeckApiTokenException if the token is invalid (in case of token-based authentication)
     */
    public RundeckSystemInfo getSystemInfo() throws RundeckApiException, RundeckApiLoginException,
            RundeckApiTokenException {
        return new ApiCall(this).get(new ApiPathBuilder("/system/info"), new SystemInfoParser("/system"));
    }


    /*
     * API token
     */

    /**
     * List API tokens for a user.
     * @param user username
     * @return list of tokens
     * @throws RundeckApiException
     */
    public List<RundeckToken> listApiTokens(final String user) throws RundeckApiException {
        AssertUtil.notNull(user, "user is mandatory to list API tokens for a user.");
        if (getApiVersion() <= Version.V18.versionNumber) {
            return new ApiCall(this).
                    get(new ApiPathBuilder("/tokens/", user),
                            new ListParser<RundeckToken>(new RundeckTokenParser(), "/tokens/token"));
        }
        else {
            return new ApiCall(this).
                    get(new ApiPathBuilder("/tokens/", user),
                            new ListParser<RundeckToken>(new RundeckTokenParserV19(), "/tokens/token"));
        }
    }

    /**
     * List all API tokens
     * @return list of tokens
     * @throws RundeckApiException
     */
    public List<RundeckToken> listApiTokens() throws RundeckApiException {
        if (getApiVersion() <= Version.V18.versionNumber) {
            return new ApiCall(this).
                get(new ApiPathBuilder("/tokens"),
                        new ListParser<RundeckToken>(new RundeckTokenParser(), "/tokens/token"));
        }
        else {
            return new ApiCall(this).
                    get(new ApiPathBuilder("/tokens"),
                            new ListParser<RundeckToken>(new RundeckTokenParserV19(), "/tokens/token"));
        }
    }

    /**
     * Generate an API token for a user.
     * @param user
     * @return
     * @throws RundeckApiException
     */
    public String generateApiToken(final String user) throws RundeckApiException{
        AssertUtil.notNull(user, "user is mandatory to generate an API token for a user.");
        RundeckToken result = new ApiCall(this).
                post(new ApiPathBuilder("/tokens/", user).emptyContent(),
                        new RundeckTokenParser("/token"));
        return result.getToken();
    }

    /**
     * Generates an api token with specific roles
     * @param user
     * @param roles
     * @return
     * @throws RundeckApiException
     */
    public String generateApiToken(final String user, final Set<String> roles) throws RundeckApiException{
        AssertUtil.notNull(user, "user is mandatory to generate an API token for a user.");
        RundeckToken request = new RundeckToken();
        request.setUser(user);
        request.setRoles(roles);
        RundeckToken result = new ApiCall(this).
                post(new ApiPathBuilder("/tokens").xml(new TokenGenerator(request).generateXmlDocument()),
                        new RundeckTokenParserV19("/token"));
        return result.getToken();
    }
    /**
     * Delete an existing token
     * @param token
     * @return
     * @throws RundeckApiException
     */
    public boolean deleteApiToken(final String token) throws RundeckApiException{
        AssertUtil.notNull(token, "token is mandatory to delete an API token.");
        new ApiCall(this).delete(new ApiPathBuilder("/token/", token));
        return true;
    }
    /**
     * Return user info for an existing token
     * @param token
     * @return token info
     * @throws RundeckApiException
     */
    public RundeckToken getApiToken(final String token) throws RundeckApiException{
        AssertUtil.notNull(token, "token is mandatory to get an API token.");
        return new ApiCall(this).get(new ApiPathBuilder("/token/", token), new RundeckTokenParser("/token"));
    }

    /**
     * Store an key file
     * @param path ssh key storage path, must start with "keys/"
     * @param keyfile key file
     * @param privateKey true to store private key, false to store public key
     * @return the key resource
     * @throws RundeckApiException
     */
    public KeyResource storeKey(final String path, final File keyfile, boolean privateKey) throws RundeckApiException{
        AssertUtil.notNull(path, "path is mandatory to store an key.");
        AssertUtil.notNull(keyfile, "keyfile is mandatory to store an key.");
        if (!path.startsWith(STORAGE_KEYS_PATH)) {
            throw new IllegalArgumentException("key storage path must start with: " + STORAGE_KEYS_PATH);
        }
        return new ApiCall(this).post(
                new ApiPathBuilder(STORAGE_ROOT_PATH, path).content(
                        privateKey ? "application/octet-stream" : "application/pgp-keys",
                        keyfile
                ),
                new SSHKeyResourceParser("/resource")
        );
    }

    /**
     * Get metadata for an key file
     *
     * @param path ssh key storage path, must start with "keys/"
     *
     * @return the ssh key resource
     *
     * @throws RundeckApiException if there is an error, or if the path is a directory not a file
     */
    public KeyResource getKey(final String path) throws RundeckApiException {
        AssertUtil.notNull(path, "path is mandatory to get an key.");
        if (!path.startsWith(STORAGE_KEYS_PATH)) {
            throw new IllegalArgumentException("key storage path must start with: " + STORAGE_KEYS_PATH);
        }
        KeyResource storageResource = new ApiCall(this).get(
                new ApiPathBuilder(STORAGE_ROOT_PATH, path),
                new SSHKeyResourceParser("/resource")
        );
        if (storageResource.isDirectory()) {
            throw new RundeckApiException("Key Path is a directory: " + path);
        }
        return storageResource;
    }

    /**
     * Get content for a public key file
     * @param path ssh key storage path, must start with "keys/"
     * @param out outputstream to write data to
     *
     * @return length of written data
     * @throws RundeckApiException
     */
    public int getPublicKeyContent(final String path, final OutputStream out) throws
            RundeckApiException, IOException {
        AssertUtil.notNull(path, "path is mandatory to get an key.");
        if (!path.startsWith(STORAGE_KEYS_PATH)) {
            throw new IllegalArgumentException("key storage path must start with: " + STORAGE_KEYS_PATH);
        }
        try {
            return new ApiCall(this).get(
                    new ApiPathBuilder(STORAGE_ROOT_PATH, path)
                            .accept("application/pgp-keys")
                            .requireContentType("application/pgp-keys"),
                    out
            );
        } catch (RundeckApiException.RundeckApiHttpContentTypeException e) {
            throw new RundeckApiException("Requested Key path was not a Public key: " + path, e);
        }
    }

    /**
     * Get content for a public key file
     * @param path ssh key storage path, must start with "keys/"
     * @param out file to write data to
     * @return length of written data
     * @throws RundeckApiException
     */
    public int getPublicKeyContent(final String path, final File out) throws
            RundeckApiException, IOException {
        final FileOutputStream fileOutputStream = new FileOutputStream(out);
        try {
            return getPublicKeyContent(path, fileOutputStream);
        }finally {
            fileOutputStream.close();
        }
    }

    /**
     * List contents of root key directory
     *
     * @return list of key resources
     * @throws RundeckApiException
     */
    public List<KeyResource> listKeyDirectoryRoot() throws RundeckApiException {
        return listKeyDirectory(STORAGE_KEYS_PATH);
    }
    /**
     * List contents of key directory
     *
     * @param path ssh key storage path, must start with "keys/"
     *
     * @throws RundeckApiException if there is an error, or if the path is a file not a directory
     */
    public List<KeyResource> listKeyDirectory(final String path) throws RundeckApiException {
        AssertUtil.notNull(path, "path is mandatory to get an key.");
        if (!path.startsWith(STORAGE_KEYS_PATH)) {
            throw new IllegalArgumentException("key storage path must start with: " + STORAGE_KEYS_PATH);
        }
        KeyResource storageResource = new ApiCall(this).get(
                new ApiPathBuilder(STORAGE_ROOT_PATH, path),
                new SSHKeyResourceParser("/resource")
        );
        if(!storageResource.isDirectory()) {
            throw new RundeckApiException("key path is not a directory path: " + path);
        }
        return storageResource.getDirectoryContents();
    }

    /**
     * Delete an key file
     * @param path a path to a key file, must start with "keys/"
     */
    public void deleteKey(final String path){
        AssertUtil.notNull(path, "path is mandatory to delete an key.");
        if (!path.startsWith(STORAGE_KEYS_PATH)) {
            throw new IllegalArgumentException("key storage path must start with: " + STORAGE_KEYS_PATH);
        }
        new ApiCall(this).delete(new ApiPathBuilder(STORAGE_ROOT_PATH, path));
    }

    /**
     * @return the URL of the Rundeck instance ("http://localhost:4440", "http://rundeck.your-compagny.com/", etc)
     */
    public String getUrl() {
        return url;
    }

    /**
     * @return the auth-token used for authentication on the Rundeck instance (null if using login-based or session-based auth)
     */
    public String getToken() {
        return token;
    }

    /**
     * @return the login used for authentication on the Rundeck instance (null if using token-based or session-based auth)
     */
    public String getLogin() {
        return login;
    }

    /**
     * @return the password used for authentication on the Rundeck instance (null if using token-based or session-based auth)
     */
    public String getPassword() {
        return password;
    }

    /**
     * @return the sessionID used for authentication on the Rundeck instance (null if using login-based or token-based auth)
     */
    public String getSessionID() {
        return sessionID;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("RundeckClient ").append(API_VERSION);
        str.append(" [").append(url).append("] ");
        if (token != null) {
            str.append("(token=").append(token).append(")");
        } else {
            str.append("(credentials=").append(login).append("|").append(password).append(")");
        }
        return str.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((login == null) ? 0 : login.hashCode());
        result = prime * result + ((password == null) ? 0 : password.hashCode());
        result = prime * result + ((token == null) ? 0 : token.hashCode());
        result = prime * result + ((url == null) ? 0 : url.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RundeckClient other = (RundeckClient) obj;
        if (login == null) {
            if (other.login != null)
                return false;
        } else if (!login.equals(other.login))
            return false;
        if (password == null) {
            if (other.password != null)
                return false;
        } else if (!password.equals(other.password))
            return false;
        if (token == null) {
            if (other.token != null)
                return false;
        } else if (!token.equals(other.token))
            return false;
        if (url == null) {
            if (other.url != null)
                return false;
        } else if (!url.equals(other.url))
            return false;
        return true;
    }

}
