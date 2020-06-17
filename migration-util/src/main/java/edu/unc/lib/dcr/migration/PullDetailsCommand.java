/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.dcr.migration;

import static edu.unc.lib.dcr.migration.MigrationConstants.OUTPUT_LOGGER;
import static edu.unc.lib.dcr.migration.MigrationConstants.toBxc3Uri;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty.createdDate;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty.lastModifiedDate;
import static edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.Relationship.originalDeposit;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.ORIGINAL_DS;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.getObjectModel;
import static edu.unc.lib.dcr.migration.fcrepo3.FoxmlDocumentHelpers.listDatastreamVersions;
import static edu.unc.lib.dl.xml.SecureXMLFactory.createSAXBuilder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.slf4j.Logger;

import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.FedoraProperty;
import edu.unc.lib.dcr.migration.fcrepo3.ContentModelHelper.Relationship;
import edu.unc.lib.dcr.migration.fcrepo3.DatastreamVersion;
import edu.unc.lib.dl.exceptions.RepositoryException;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadata;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadataBean;
import edu.unc.lib.dl.search.solr.util.SolrSettings;
import edu.unc.lib.dl.util.URIUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author bbpennel
 */
@Command(name = "pull_details", aliases = {"pd"})
public class PullDetailsCommand implements Callable<Integer> {
    private static final Logger output = getLogger(OUTPUT_LOGGER);

    @Parameters(index = "0")
    private Path idListPath;

    @Option(names = {"-o"},
            description = "Output file path")
    protected Path outputPath;

    @Option(names = {"-b", "--fedora-uri"},
            defaultValue = "http://localhost/fedora",
            description = "Base uri name for fedora. Default localhost.")
    protected String fedoraUri;

    @Option(names = {"-u", "--fedora-user"},
            defaultValue = "fedoraRead",
            description = "Username for fedora. Default fedoraAdmin.")
    protected String fedoraUser;

    @Option(names = {"-p", "--fedora-password"},
            interactive = true,
            description = "Password for fedora")
    protected String fedoraPassword;

    @Option(names = {"-s", "--solr-uri"},
            defaultValue = "http://localhost/solr",
            description = "URI for the solr instance. Default localhost")
    protected String solrUri;

    @Option(names = {"-t", "--duplicate-titles"},
            description = "Report the ids of other objects with the same title")
    private boolean reportDupeTitles;

    @Option(names = {"-m", "--duplicate-md5"},
            description = "Report the ids of other files with the same md5")
    private boolean reportDupeMd5;

    @Option(names = {"-c", "--dangling-contains"},
            description = "Report the ids of any containment references children that do not exist")
    private boolean reportDanglingContains;

    @Option(names = {"--has-ancestor"},
            description = "Checks to see if each entry is a descedent of the provided id")
    protected String hasAncestor;

    private static final String[] DEFAULT_HEADERS = new String[] {
        "id", "title", "deposited", "lastModified", "depositRec", "isDeleted", "numChildren"
    };

    private static final String DANGLING_CONTAINS_HEADER = "danglingContains";
    private static final String DUPE_TITLE_HEADER = "dupeTitles";
    private static final String DUPE_MD5_HEADER = "dupeMd5";
    private static final String HAS_ANCESTOR_HEADER = "hasAncestor";

    private CloseableHttpClient client;
    private SolrClient solr;

    private void setupDependencies() {
        CredentialsProvider provider = new BasicCredentialsProvider();
        UsernamePasswordCredentials credentials =
                new UsernamePasswordCredentials(fedoraUser, fedoraPassword);
        provider.setCredentials(AuthScope.ANY, credentials);

        client = HttpClientBuilder.create()
                .setDefaultCredentialsProvider(provider)
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .build();

        solr = new HttpSolrClient.Builder(solrUri)
                .withHttpClient(client)
                .build();
    }

    @Override
    public Integer call() throws Exception {
        output.info("Retrieving details");

        setupDependencies();

        List<String> headerList = new ArrayList<>(Arrays.asList(DEFAULT_HEADERS));
        if (reportDupeTitles) {
            headerList.add(DUPE_TITLE_HEADER);
        }
        if (reportDupeMd5) {
            headerList.add(DUPE_MD5_HEADER);
        }
        if (reportDanglingContains) {
            headerList.add(DANGLING_CONTAINS_HEADER);
        }
        if (hasAncestor != null) {
            headerList.add(HAS_ANCESTOR_HEADER);
        }

        try (
                Writer writer = getOutputWriter();
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT
                        .withHeader(headerList.toArray(new String[0])))
                ) {
            Files.lines(idListPath, UTF_8).forEach(line -> {
                String id = line.trim();
                try {
                    reportObject(id, printer);
                } catch (IOException e) {
                    output.error("Failed to write csv for {}", id, e);
                }
            });
        }

        return 0;
    }

    private void reportObject(String id, CSVPrinter printer) throws IOException {
        String title = "";
        boolean deleted = false;
        String created;
        String updated;
        String depRecUri = "";
        String dataFileMd5 = null;
        int numChildren;
        List<Statement> contains;
        PID originalPid = PIDs.get(id);

        List<Object> fields;

        URI requestUri = URI.create(URIUtil.join(fedoraUri, "objects",
                id, "objectXML"));
        HttpGet httpGet = new HttpGet(requestUri);
        try (CloseableHttpResponse resp = client.execute(httpGet)) {
            Document foxml;
            try {
                foxml = createSAXBuilder().build(resp.getEntity().getContent());
            } catch (IOException | JDOMException e) {
                throw new RepositoryException("Failed to read FOXML for " + originalPid, e);
            }

            Model model = getObjectModel(foxml);
            Resource bxc3Resc = model.getResource(toBxc3Uri(originalPid));

            created = bxc3Resc.getProperty(createdDate.getProperty()).getString();
            updated = bxc3Resc.getProperty(lastModifiedDate.getProperty()).getString();
            if (bxc3Resc.hasProperty(originalDeposit.getProperty())) {
                depRecUri = bxc3Resc.getProperty(originalDeposit.getProperty()).getResource().getURI();
                depRecUri = StringUtils.substringAfterLast(depRecUri, "/");
            }
            if (bxc3Resc.hasProperty(FedoraProperty.label.getProperty())) {
                title = bxc3Resc.getProperty(FedoraProperty.label.getProperty()).getString();
            }
            contains = bxc3Resc.listProperties(Relationship.contains.getProperty()).toList();
            numChildren = contains.size();
            if (bxc3Resc.hasProperty(FedoraProperty.state.getProperty())) {
                deleted = bxc3Resc.hasLiteral(FedoraProperty.state.getProperty(), "Deleted");
            }

            List<DatastreamVersion> originalVersions = listDatastreamVersions(foxml, ORIGINAL_DS);
            if (originalVersions != null && originalVersions.size() > 0) {
                DatastreamVersion lastV = originalVersions.get(originalVersions.size() - 1);
                dataFileMd5 = lastV.getMd5();
            }

        } catch (Exception e) {
            output.error("Failed to retrieve foxml record {}", id, e);
            return;
        }

        String escapedId = id.replace(":", "\\:");
        SolrQuery solrDetailsQuery = new SolrQuery();
        solrDetailsQuery.setQuery("id:" + escapedId);
        solrDetailsQuery.setRows(1);
        solrDetailsQuery.setFields("id", "status", "title", "ancestorIds");
        QueryResponse solrResp;
        try {
            solrResp = solr.query(solrDetailsQuery);

            List<BriefObjectMetadataBean> details = solrResp.getBeans(BriefObjectMetadataBean.class);
            BriefObjectMetadata md = null;
            if (details.size() > 0) {
                md = details.get(0);
                title = md.getTitle();
                deleted = deleted || md.getStatus().contains("Deleted")
                        || md.getStatus().contains("Parent Deleted");
            } else {
                output.warn("No solr record for {}", id);
            }

            fields = new ArrayList<>(Arrays.asList(id, title, created, updated, depRecUri, deleted, numChildren));

            reportDuplicateTitles(fields, title, escapedId);

            reportDuplicateMd5s(fields, dataFileMd5, escapedId);

            reportDanglingContains(fields, contains);

            reportHasAncestor(fields, md);
        } catch (SolrServerException | IOException e) {
            output.error("Failed to query for {}", id, e);
            return;
        }

        printer.printRecord(fields);
    }

    private void reportDuplicateTitles(List<Object> fields, String title, String escapedId)
            throws SolrServerException, IOException {
        if (reportDupeTitles) {
            SolrQuery titleDupeQuery = new SolrQuery();
            String escapedTitle = SolrSettings.escapeQueryChars(title);
            titleDupeQuery.setQuery(String.format("-id:%s AND titleIndex:\"%s\"",
                    escapedId, escapedTitle.toLowerCase()));
            fields.add(findPossibleDuplicates(titleDupeQuery));
        }
    }

    private void reportDuplicateMd5s(List<Object> fields, String dataFileMd5, String escapedId)
            throws SolrServerException, IOException {
        if (reportDupeMd5) {
            SolrQuery md5DupeQuery = new SolrQuery();
            md5DupeQuery.setQuery(String.format("-id:%s AND datastream:\"DATA_FILE|*|%s|\"",
                    escapedId, dataFileMd5));
            fields.add(findPossibleDuplicates(md5DupeQuery));
        }
    }

    private void reportDanglingContains(List<Object> fields, List<Statement> contains) throws IOException {
        if (reportDanglingContains) {
            List<String> danglers = new ArrayList<>();

            for (Statement containsStmt : contains) {
                String id = substringAfterLast(containsStmt.getResource().getURI(), "/");
                URI existsUri = URI.create(URIUtil.join(fedoraUri, "objects", id));
                HttpGet existsGet = new HttpGet(existsUri);
                try (CloseableHttpResponse resp = client.execute(existsGet)) {
                    if (resp.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                        danglers.add(id);
                    }
                }
            }

            fields.add(String.join(",", danglers));
        }
    }

    private String findPossibleDuplicates(SolrQuery dupeQuery) throws SolrServerException, IOException {
        dupeQuery.setFields("id");
        dupeQuery.setRows(5);

        QueryResponse solrResp = solr.query(dupeQuery);
        List<BriefObjectMetadataBean> otherResults = solrResp.getBeans(BriefObjectMetadataBean.class);

        if (otherResults.size() > 0) {
            return otherResults.stream()
                    .map(BriefObjectMetadata::getId)
                    .collect(Collectors.joining(","));
        } else {
            return "";
        }
    }

    private void reportHasAncestor(List<Object> fields, BriefObjectMetadata md) {
        if (hasAncestor != null && md != null) {
            fields.add(md.getAncestorIds().contains(hasAncestor));
        }
    }

    private Writer getOutputWriter() throws IOException {
        if (outputPath == null) {
            return new PrintWriter(System.out);
        } else {
            return new FileWriter(outputPath.toFile());
        }
    }
}
