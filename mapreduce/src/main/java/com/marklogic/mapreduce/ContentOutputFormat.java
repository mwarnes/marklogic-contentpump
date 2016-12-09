/*
 * Copyright 2003-2016 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DefaultStringifier;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.marklogic.mapreduce.utilities.AssignmentManager;
import com.marklogic.mapreduce.utilities.AssignmentPolicy;
import com.marklogic.mapreduce.utilities.ForestInfo;
import com.marklogic.mapreduce.utilities.InternalUtilities;
import com.marklogic.mapreduce.utilities.RestrictedHostsUtil;
import com.marklogic.mapreduce.utilities.TextArrayWritable;
import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.ContentCapability;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultItem;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;
import com.marklogic.xcc.types.XSBoolean;
import com.marklogic.xcc.types.XSInteger;

/**
 * MarkLogicOutputFormat for Content.
 * 
 * <p>
 *  Use this class to store results as content in a MarkLogic Server database.
 *  The text, XML, or binary content is inserted into the database at the
 *  given {@link DocumentURI}.
 * </p>
 * <p>
 *  When using this {@link MarkLogicOutputFormat}, your key should be the URI of
 *  the document to insert into the database. The value should be the content to
 *  insert, in the form of {@link org.apache.hadoop.io.Text} or 
 *  {@link MarkLogicNode}.
 * </p>
 * <p>
 *  Several configuration properties exist for controlling the content insertion,
 *  including permissions, collections, quality, directory, and content type.
 * </p>
 * 
 * @see MarkLogicConstants
 * @see com.marklogic.mapreduce.examples.ContentLoader
 * @see com.marklogic.mapreduce.examples.ZipContentLoader
 * @author jchen
 *
 * @param <VALUEOUT>
 */
public class ContentOutputFormat<VALUEOUT> extends
        MarkLogicOutputFormat<DocumentURI, VALUEOUT> {
    public static final Log LOG = LogFactory.getLog(ContentOutputFormat.class);
    
    // Prepend to a forest id to form a database name parsed by XDBC.
    // Also used here alone as the forest id placeholder in non-fast-mode.
    public static final String ID_PREFIX = "#";
    
    static final String FOREST_HOST_MAP_QUERY =
        "import module namespace hadoop = " +
        "\"http://marklogic.com/xdmp/hadoop\" at \"/MarkLogic/hadoop.xqy\";\n"+
        "hadoop:get-forest-host-map()";
    public static final String FOREST_HOST_QUERY =
        "import module namespace hadoop = " +
        "\"http://marklogic.com/xdmp/hadoop\" at \"/MarkLogic/hadoop.xqy\";\n"+
        "declare variable $policy as xs:string external;\n" +
        "declare variable $partition-name as xs:string external;\n" + 
        "hadoop:get-forest-host($policy,$partition-name)";
    public static final String FOREST_REPLICA_HOST_QUERY =
        "import module namespace hadoop = " +
        "\"http://marklogic.com/xdmp/hadoop\" at \"/MarkLogic/hadoop.xqy\";\n"+
        "declare variable $policy as xs:string external;\n" +
        "declare variable $partition-name as xs:string external;\n" + 
        "hadoop:get-forest-replica-host($policy,$partition-name)";
    public static final String INIT_QUERY =
        "import module namespace hadoop = "
        + "\"http://marklogic.com/xdmp/hadoop\" at \"/MarkLogic/hadoop.xqy\";\n"
        + "xdmp:host-name(xdmp:host()), \n"
        + "let $versionf := "
        + "  fn:function-lookup(xs:QName('xdmp:effective-version'),0)\n"
        + "return if (exists($versionf)) then $versionf() else 0, \n"
        + "let $repf := "
        + "  fn:function-lookup(xs:QName('hadoop:get-forest-replica-host'),2)\n"
        + "return exists($repf),"
        + "let $f := "
        + "  fn:function-lookup(xs:QName('hadoop:get-assignment-policy'),0)\n"
        + "return if (exists($f)) then $f() else ()";
    
    protected AssignmentManager am = AssignmentManager.getInstance();
    protected boolean fastLoad;
    /** whether stats-based policy allows fastload **/
    protected boolean allowFastLoad = true;
    protected AssignmentPolicy.Kind policy;
    protected boolean legacy = false;
    protected boolean failover = false;
    protected String initHostName;
    protected long effectiveVersion;
    @Override
    public void checkOutputSpecs(Configuration conf, ContentSource cs)
    throws IOException { 
        Session session = null;
        ResultSequence result = null;
        try {
            session = cs.newSession(); 
            RequestOptions options = new RequestOptions();
            options.setDefaultXQueryVersion("1.0-ml");
            session.setDefaultRequestOptions(options);
            
            // clear output dir if specified
            String outputDir = conf.get(OUTPUT_DIRECTORY);
            if (outputDir != null) {
                outputDir = outputDir.endsWith("/") ? 
                        outputDir : outputDir + "/";
                if (conf.getBoolean(OUTPUT_CLEAN_DIR, false)) {
                    // delete directory if exists
                    String queryText = DELETE_DIRECTORY_TEMPLATE.replace(
                            DIRECTORY_TEMPLATE, outputDir);
                    AdhocQuery query = session.newAdhocQuery(queryText);
                    result = session.submitRequest(query);
                } else { // ensure nothing exists under output dir
                    String queryText = CHECK_DIRECTORY_EXIST_TEMPLATE.replace(
                            DIRECTORY_TEMPLATE, outputDir);
                    AdhocQuery query = session.newAdhocQuery(queryText);
                    result = session.submitRequest(query);
                    if (result.hasNext()) {
                        ResultItem item = result.next();
                        if (((XSBoolean)(item.getItem())).asBoolean()) {
                            throw new IllegalStateException("Directory " + 
                                    outputDir + " already exists");
                        }
                    } else {
                        throw new IllegalStateException(
                                "Failed to query directory content.");
                    }
                }
            }
            boolean restrictHosts = conf.getBoolean(OUTPUT_RESTRICT_HOSTS, false);
            // initialize server host name and assignment policy
            initialize(session, restrictHosts);
            
            // ensure manual directory creation 
            if (fastLoad) {
                LOG.info("Running in fast load mode");
                    // store forest-info map into config system
                DefaultStringifier.store(conf, queryForestInfo(cs),
                    OUTPUT_FOREST_HOST);

                AdhocQuery query = 
                		session.newAdhocQuery(DIRECTORY_CREATE_QUERY);
                result = session.submitRequest(query);
                if (result.hasNext()) {
                    ResultItem item = result.next();
                    String dirMode = item.asString();
                    if (!dirMode.equals(MANUAL_DIRECTORY_MODE)) {
                        throw new IllegalStateException(
                                "Manual directory creation mode is required. " +
                                "The current creation mode is " + dirMode + ".");
                    }
                } else {
                    throw new IllegalStateException(
                            "Failed to query directory creation mode.");
                }
            } else {
                String[] outputHosts = conf.getStrings(OUTPUT_HOST);
                TextArrayWritable hostArray = null;
                if (restrictHosts) {
                    hostArray = new TextArrayWritable(outputHosts);
                } else {
                    String outputHost = outputHosts.length>0?outputHosts[0]:null;
                    // 23798: replace hostname in forest config with 
                    // user-specified output host
                    if (MODE_LOCAL.equals(conf.get(EXECUTION_MODE))) {
                        hostArray = queryHosts(cs, initHostName, outputHost);
                    } else {
                        hostArray = queryHosts(cs);
                    }
                }
                DefaultStringifier.store(conf, hostArray, OUTPUT_FOREST_HOST);
            }
    
            // validate capabilities
            String[] perms = conf.getStrings(OUTPUT_PERMISSION);
            if (perms != null && perms.length > 0) {
                if (perms.length % 2 != 0) {
                    throw new IllegalStateException(
                    "Permissions are expected to be in <role, capability> pairs.");
                }
                int i = 0;
                while (i + 1 < perms.length) {
                    String roleName = perms[i++];
                    if (roleName == null || roleName.isEmpty()) {
                        throw new IllegalStateException(
                                "Illegal role name: " + roleName);
                    }
                    String perm = perms[i].trim();
                    if (!perm.equalsIgnoreCase(ContentCapability.READ.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.EXECUTE.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.INSERT.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.UPDATE.toString()) &&
                        !perm.equalsIgnoreCase(ContentCapability.NODE_UPDATE.toString())) {
                        throw new IllegalStateException("Illegal capability: " + perm);
                    }
                    i++;
                }
            }
        } catch (RequestException ex) {
            throw new IOException(ex);
        } finally {
            if (session != null) {
                session.close();
            } 
            if (result != null) {
                result.close();
            }
        }
    }
    
    protected Map<String, ContentSource> getSourceMap(boolean fastLoad, 
    		TaskAttemptContext context) throws IOException{
        Configuration conf = context.getConfiguration();
        Map<String, ContentSource> sourceMap = 
            new LinkedHashMap<String, ContentSource>();
        if (fastLoad) {
            LinkedMapWritable forestStatusMap = getForestStatusMap(conf);
            String[] outputHosts = conf.getStrings(OUTPUT_HOST);
            // Fastload import needs restrictHosts info from conf for 
            // multiple instances of ContentOutputFormat created by 
            // MultiThreadedMapper. It can't be saved as instance member 
            // because initialize() is only called once in LocalJobRunner
            boolean restrictHosts = conf.getBoolean(OUTPUT_RESTRICT_HOSTS, false);
            RestrictedHostsUtil rhUtil = null;
            // construct forest->contentSource map
            Map<String, ContentSource> hostSourceMap = 
                    new HashMap<String, ContentSource>();
            if (restrictHosts) {
                rhUtil = new RestrictedHostsUtil(outputHosts);
                for (Writable forestId : forestStatusMap.keySet()) {
                    String forestHost = ((ForestInfo)forestStatusMap.get(forestId))
                            .getHostName();
                    rhUtil.addForestHost(forestHost);
                }
            }
            for (Writable forestId : forestStatusMap.keySet()) {
                ForestInfo fs = (ForestInfo)forestStatusMap.get(forestId);
                String forestIdStr = ((Text)forestId).toString();
                String forestHost = fs.getHostName();
                String targetHost = restrictHosts?
                        rhUtil.getNextHost(forestHost):forestHost;
                if (fs.getUpdatable() &&
                        hostSourceMap.get(targetHost) == null) {
                    try {
                        ContentSource cs = InternalUtilities.getOutputContentSource(
                                conf, targetHost);
                        hostSourceMap.put(targetHost, cs);
                    } catch (XccConfigException e) {
                        throw new IOException(e);
                    }
                }
                sourceMap.put(ID_PREFIX + forestIdStr,
                        hostSourceMap.get(targetHost));
            }
        } else {
            TextArrayWritable hosts = getHosts(conf);
            for (Writable host : hosts.get()) {
                String hostStr = host.toString();
                try {
                    ContentSource cs = InternalUtilities.getOutputContentSource(
                        conf, hostStr);
                    sourceMap.put(hostStr, cs);
                } catch (XccConfigException e) {
                    throw new IOException(e);
                }
            } 
        }
        return sourceMap;
    }
    
    @Override
    public RecordWriter<DocumentURI, VALUEOUT> getRecordWriter(
            TaskAttemptContext context) throws IOException, InterruptedException {
        Configuration conf = context.getConfiguration();
        // TODO: if MAPREDUCE-3377 still exists, need to re-run initialize
        fastLoad = Boolean.valueOf(conf.get(OUTPUT_FAST_LOAD));
        Map<String, ContentSource> sourceMap = getSourceMap(fastLoad, context);
        // construct the ContentWriter
        return new ContentWriter<VALUEOUT>(conf, sourceMap, fastLoad, 
                am, effectiveVersion);
    }
    
    // forest host map is saved when checkOutputSpecs() is called.  In certain 
    // versions of Hadoop, the config is not persisted as part of the job hence
    // will be lost.  See MAPREDUCE-3377 for details.  When this entry cannot
    // be found from the config, re-query the database to get this info.  It is
    // possible that each task gets a different version of the map if the 
    // forest config changes while the job runs.
    protected LinkedMapWritable getForestStatusMap(Configuration conf) 
    throws IOException {
        String forestHost = conf.get(OUTPUT_FOREST_HOST);
        if (forestHost != null) {
            //Restores the object from the configuration.
            LinkedMapWritable fhmap = DefaultStringifier.load(conf, OUTPUT_FOREST_HOST, 
                LinkedMapWritable.class);
            // must be in fast load mode, otherwise won't reach here
            String s = conf.get(ASSIGNMENT_POLICY);
            //EXECUTION_MODE must have a value in mlcp;
            //default is "distributed" in hadoop connector
            String mode = conf.get(EXECUTION_MODE, MODE_DISTRIBUTED);
            if (MODE_DISTRIBUTED.equals(mode)) {
            	AssignmentPolicy.Kind policy =
            			AssignmentPolicy.Kind.forName(s);
                am.initialize(policy, fhmap, conf.getInt(BATCH_SIZE, 10));
            }
            return fhmap;
        } else {
            try {
                String[] outputHosts = conf.getStrings(OUTPUT_HOST);
                boolean restrictHosts = conf.getBoolean(OUTPUT_RESTRICT_HOSTS, false);
                if (outputHosts == null || outputHosts.length == 0) {
                    throw new IllegalStateException(OUTPUT_HOST +
                            " is not specified.");
                }
                // try getting a connection
                ContentSource cs = 
                    InternalUtilities.getOutputContentSource(conf, 
                            outputHosts[0]);
                //get policy
                initialize(cs.newSession(), restrictHosts);
                // query forest status mapping
                return queryForestInfo(cs);
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }
    }
    
    /**
     * Initialize initial server host name, assignment policy and fastload.
     * 
     * @param session
     * @throws IOException
     * @throws RequestException
     */
    protected void initialize(Session session, boolean restrictHosts)
        throws IOException, RequestException {
        AdhocQuery query = session.newAdhocQuery(INIT_QUERY);
        RequestOptions options = new RequestOptions();
        options.setDefaultXQueryVersion("1.0-ml");
        query.setOptions(options);
        ResultSequence result = null;
        result = session.submitRequest(query);

        ResultItem item = result.next();
        initHostName = item.asString();
        item = result.next();
        effectiveVersion = ((XSInteger)item.getItem()).asLong();
        item = result.next();
        failover = !restrictHosts && item.asString().equals("true");
        if (result.hasNext()) {
            item = result.next();
            String policyStr = item.asString();
            conf.set(ASSIGNMENT_POLICY, policyStr);
            policy = AssignmentPolicy.Kind.forName(policyStr);
            item = result.next();
            allowFastLoad = Boolean.parseBoolean(item.asString());
            if ((policy == AssignmentPolicy.Kind.STATISTICAL 
                || policy == AssignmentPolicy.Kind.RANGE
                || policy == AssignmentPolicy.Kind.QUERY)
                && !allowFastLoad && conf.getBoolean(OUTPUT_FAST_LOAD, false)) {
                throw new IOException(
                    "Fastload can't be used: rebalancer is on and "
                        + "forests are imbalanced in a database with "
                        + "statistics-based assignment policy");
            }
        } else {
        	policy = AssignmentPolicy.Kind.LEGACY;
        	legacy = true;
        }
        
        // initialize fastload mode
        if (conf.get(OUTPUT_FAST_LOAD) == null) {
            // fastload not set
            if (conf.get(OUTPUT_DIRECTORY) != null) {
                // output_dir is set, attempt to do fastload
                if(conf.get(OUTPUT_PARTITION) == null && 
                   (policy == AssignmentPolicy.Kind.RANGE ||
                    policy == AssignmentPolicy.Kind.QUERY)) {
                    fastLoad = false;
                } else if (policy == AssignmentPolicy.Kind.RANGE ||
                           policy == AssignmentPolicy.Kind.QUERY ||
                	   policy == AssignmentPolicy.Kind.STATISTICAL) {
                    fastLoad = allowFastLoad;
                } else {
                	fastLoad = true;
                }
            } else {
                //neither fastload nor output_dir is set
                fastLoad = false;
            }
        } else {
            fastLoad = conf.getBoolean(OUTPUT_FAST_LOAD, false);
            if (fastLoad && conf.get(OUTPUT_PARTITION) == null
                && (policy == AssignmentPolicy.Kind.RANGE ||
                    policy == AssignmentPolicy.Kind.QUERY)) {
                throw new IllegalArgumentException(
                    "output_partition is required for fastload mode.");
            }
        }
        conf.setBoolean(OUTPUT_FAST_LOAD, fastLoad);
    }

    /**
     * must be attempting or doing fastload when this method is called.
     * result format of the query varies based on policy
     * 
     * bucket:(fid, host, updateAllow)*
     * range:(fid, host, fragmentCount)*
     * statistical: (fid, host, fragmentCount)*
     * legacy: (fid, host)*
     * 
     * @param cs
     * @return a forest-info map
     * @throws IOException
     */
    protected LinkedMapWritable queryForestInfo(ContentSource cs) 
    throws IOException {
        Session session = null;
        ResultSequence result = null;
        try {
            session = cs.newSession();   
            AdhocQuery query = null;
            if (legacy) {             
                LOG.debug("Legacy assignment is assumed for older MarkLogic" + 
                          " Server.");
                query = session.newAdhocQuery(FOREST_HOST_MAP_QUERY);
            } else {
                if (failover) {
                  query = session.newAdhocQuery(FOREST_REPLICA_HOST_QUERY);
                } else {
                  query = session.newAdhocQuery(FOREST_HOST_QUERY);
                }
                if (policy == AssignmentPolicy.Kind.RANGE ||
                    policy == AssignmentPolicy.Kind.QUERY) {
                    String pName = conf.get(OUTPUT_PARTITION);
                    query.setNewStringVariable("partition-name", pName);
                } else {
                    query.setNewStringVariable("partition-name", "");
                }
                query.setNewStringVariable("policy", 
                		policy.toString().toLowerCase());
            }

            // query forest status mapping                 
            RequestOptions options = new RequestOptions();
            options.setDefaultXQueryVersion("1.0-ml");
            query.setOptions(options);
            if(LOG.isDebugEnabled()) {
                LOG.debug(query.toString());
            }
            result = session.submitRequest(query);

            LinkedMapWritable forestStatusMap = new LinkedMapWritable();
            Text forest = null;
            Text master = null;
            String[] outputHosts = conf.getStrings(OUTPUT_HOST);
            String outputHost = outputHosts[0];
            boolean local = MODE_LOCAL.equals(conf.get(EXECUTION_MODE));
            
            while (result.hasNext()) {
                ResultItem item = result.next();
                if (forest == null) {
                    forest = new Text(item.asString());
                } else {
                    String hostName = item.asString();
                    // 23798: replace hostname in forest config with 
                    // user-specified output host
                    if (local && hostName != null && 
                        hostName.equals(initHostName)) {
                    	hostName = outputHost;
                    }
                    boolean updatable = true;
                    long dc = -1;
                    if (!legacy) {
                        if (policy == AssignmentPolicy.Kind.BUCKET) {
                            item = result.next();
                            updatable = Boolean.parseBoolean(item
                                .asString());
                        } else if (policy == AssignmentPolicy.Kind.RANGE ||
                                   policy == AssignmentPolicy.Kind.STATISTICAL ||
                                   policy == AssignmentPolicy.Kind.QUERY ) {
                            // range or statistical
                            item = result.next();
                            dc = Long.parseLong(item.asString());
                        }
                    } 
                    if (failover) {
                        item = result.next();
                        master = new Text(item.asString());
                    } else {
                        master = forest;
                    }
                    forestStatusMap.put(forest, new ForestInfo(
                        hostName, dc, updatable, master.toString()));
                    forest = null;
                    master = null;
                }
            }
            if (forestStatusMap.size() == 0) {
                throw new IOException("Target database has no forests attached: "
                    + "check forests in database");
            }
            am.initialize(policy, forestStatusMap, conf.getInt(BATCH_SIZE,10));
            return forestStatusMap;
        } catch (RequestException e) {
            LOG.error(e.getMessage(), e);
            throw new IOException(e);
        } finally {
            if (result != null) {
                result.close();
            }
            if (session != null) {
                session.close();
            }
        }    
    }   
}
