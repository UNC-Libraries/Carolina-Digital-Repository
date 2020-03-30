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
package edu.unc.lib.dl.services.camel.enhancements;

import static edu.unc.lib.dl.rdf.Fcrepo4Repository.Binary;
import static edu.unc.lib.dl.services.RunEnhancementsMessageHelpers.makeEnhancementOperationBody;
import static edu.unc.lib.dl.services.camel.enhancements.EnhancementRequestProcessor.FCREPO_RESOURCE_TYPE;
import static edu.unc.lib.dl.services.camel.util.CdrFcrepoHeaders.CdrBinaryMimeType;
import static java.util.Arrays.asList;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.jdom2.Document;
import org.jgroups.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.dl.fcrepo4.BinaryObject;
import edu.unc.lib.dl.fcrepo4.FileObject;
import edu.unc.lib.dl.fcrepo4.FolderObject;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.services.MessageSender;
import edu.unc.lib.dl.services.camel.cdrEvents.CdrEventMessageHelpers;
import edu.unc.lib.dl.services.camel.enhancements.EnhancementRequestProcessor;
import edu.unc.lib.dl.test.TestHelper;

/**
 *
 * @author lfarrell
 *
 */
public class EnhancementRequestProcessorTest {
    private EnhancementRequestProcessor processor;

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private final static String AUTHOR = "theauthor";

    private static final String FEDORA_BASE = "http://example.com/rest/";

    private static final String RESC_ID = "de75d811-9e0f-4b1f-8631-2060ab3580cc";
    private static final String RESC_URI = FEDORA_BASE + "content/de/75/d8/11/" + RESC_ID + "/original_file";

    private PID binPid;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private RepositoryObjectLoader repoObjLoader;
    @Mock
    private MessageSender messageSender;
    @Mock
    private BinaryObject binObject;

    @Captor
    private ArgumentCaptor<Document> docCaptor;

    @Before
    public void init() throws Exception {
        initMocks(this);

        TestHelper.setContentBase(FEDORA_BASE);

        processor = new EnhancementRequestProcessor();
        processor.setMessageSender(messageSender);
        processor.setRepositoryObjectLoader(repoObjLoader);

        when(exchange.getIn()).thenReturn(message);
        when(exchange.getIn().getHeader(FCREPO_URI)).thenReturn(null);

        binPid = PIDs.get(RESC_URI);

        when(binObject.getPid()).thenReturn(binPid);
        when(repoObjLoader.getRepositoryObject(binPid)).thenReturn(binObject);
    }

    @Test
    public void testUpdateHeadersText() throws Exception {
        setMessageBody(binPid);
        when(binObject.getMimetype()).thenReturn("text/plain");

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, RESC_URI);
        verify(message).setHeader(CdrBinaryMimeType, "text/plain");
        verify(message).setHeader(FCREPO_RESOURCE_TYPE, Binary.getURI());
    }

    @Test
    public void testUpdateHeadersImage() throws Exception {
        setMessageBody(binPid);
        when(binObject.getMimetype()).thenReturn("image/png");

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, RESC_URI);
        verify(message).setHeader(CdrBinaryMimeType, "image/png");
        verify(message).setHeader(FCREPO_RESOURCE_TYPE, Binary.getURI());
    }

    @Test
    public void testFileObject() throws Exception {
        PID filePid = makePid();
        FileObject fileObj = mock(FileObject.class);
        when(fileObj.getPid()).thenReturn(filePid);
        when(fileObj.getOriginalFile()).thenReturn(binObject);

        when(repoObjLoader.getRepositoryObject(filePid)).thenReturn(fileObj);

        setMessageBody(filePid);

        when(binObject.getMimetype()).thenReturn("image/png");

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, RESC_URI);
        verify(message).setHeader(CdrBinaryMimeType, "image/png");
        verify(message).setHeader(FCREPO_RESOURCE_TYPE, Binary.getURI());
    }

    @Test
    public void testFileObjectNoBinary() throws Exception {
        PID filePid = makePid();
        FileObject fileObj = mock(FileObject.class);
        when(fileObj.getPid()).thenReturn(filePid);
        when(repoObjLoader.getRepositoryObject(filePid)).thenReturn(fileObj);

        setMessageBody(filePid);

        processor.process(exchange);

        verify(message, never()).setHeader(eq(FCREPO_URI), anyString());
        verify(message, never()).setHeader(eq(CdrBinaryMimeType), anyString());
        verify(message, never()).setHeader(eq(FCREPO_RESOURCE_TYPE), anyString());
    }

    @Test
    public void testContainerNoChildren() throws Exception {
        PID folderPid = makePid();
        FolderObject folderObj = mock(FolderObject.class);
        when(folderObj.getPid()).thenReturn(folderPid);
        when(repoObjLoader.getRepositoryObject(folderPid)).thenReturn(folderObj);

        setMessageBody(folderPid);

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, folderPid.getRepositoryPath());
        verify(message, never()).setHeader(eq(CdrBinaryMimeType), anyString());
        verify(message, never()).setHeader(FCREPO_RESOURCE_TYPE, Binary.getURI());

        verify(messageSender, never()).sendMessage(any(Document.class));
    }

    @Test
    public void testContainerWithChildren() throws Exception {
        PID folderPid = makePid();
        FolderObject folderObj = mock(FolderObject.class);
        when(folderObj.getPid()).thenReturn(folderPid);
        when(repoObjLoader.getRepositoryObject(folderPid)).thenReturn(folderObj);

        PID child1Pid = makePid();
        WorkObject child1 = mock(WorkObject.class);
        when(child1.getPid()).thenReturn(child1Pid);

        PID child2Pid = makePid();
        FolderObject child2 = mock(FolderObject.class);
        when(child2.getPid()).thenReturn(child2Pid);

        when(folderObj.getMembers()).thenReturn(asList(child1, child2));

        setMessageBody(folderPid);

        processor.process(exchange);

        verify(message).setHeader(FCREPO_URI, folderPid.getRepositoryPath());
        verify(message, never()).setHeader(eq(CdrBinaryMimeType), anyString());
        verify(message, never()).setHeader(FCREPO_RESOURCE_TYPE, Binary.getURI());

        verify(messageSender, times(2)).sendMessage(docCaptor.capture());

        List<Document> msgs = docCaptor.getAllValues();
        Document msg1 = msgs.get(0);
        assertEquals(child1Pid, CdrEventMessageHelpers.extractPid(msg1));
        Document msg2 = msgs.get(1);
        assertEquals(child2Pid, CdrEventMessageHelpers.extractPid(msg2));
    }

    private void setMessageBody(PID subjPid) {
        Document msg = makeEnhancementOperationBody(AUTHOR, subjPid, null, false);

        when(message.getBody()).thenReturn(msg);
    }

    private PID makePid() {
        return PIDs.get(UUID.randomUUID().toString());
    }
}
