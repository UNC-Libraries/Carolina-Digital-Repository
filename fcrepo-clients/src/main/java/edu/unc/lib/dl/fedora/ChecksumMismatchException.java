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
package edu.unc.lib.dl.fedora;

import edu.unc.lib.boxc.model.api.exceptions.FedoraException;

/**
 * Checksum did not match the provided checksum value.
 *
 * @author bbpennel
 *
 */
public class ChecksumMismatchException extends FedoraException {

    private static final long serialVersionUID = -7456278395813803155L;

    public ChecksumMismatchException(Exception e) {
        super(e);
    }

    public ChecksumMismatchException(String message, Exception e) {
        super(message, e);
    }

    public ChecksumMismatchException(String message) {
        super(message);
    }

}
