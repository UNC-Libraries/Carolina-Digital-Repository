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
package edu.unc.lib.dl.persist.services.transfer;

import java.net.URI;

import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;

/**
 * Default implementation of a binary transfer outcome
 *
 * @author bbpennel
 */
public class BinaryTransferOutcomeImpl implements BinaryTransferOutcome {

    private URI destinationUri;
    private String sha1;

    public BinaryTransferOutcomeImpl(URI destinationUri, String sha1) {
        this.destinationUri = destinationUri;
        this.sha1 = sha1;
    }

    @Override
    public URI getDestinationUri() {
        return destinationUri;
    }

    @Override
    public String getSha1() {
        return sha1;
    }

}