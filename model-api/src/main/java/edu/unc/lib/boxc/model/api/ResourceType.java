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
package edu.unc.lib.boxc.model.api;

import java.util.List;

import org.apache.jena.rdf.model.Resource;

import edu.unc.lib.boxc.model.api.rdf.Cdr;
import edu.unc.lib.boxc.model.api.rdf.Fcrepo4Repository;

/**
 * Resource Types
 * @author bbpennel
 *
 */
public enum ResourceType {
    AdminUnit(1, Cdr.AdminUnit),
    Collection(2, Cdr.Collection),
    Folder(6, Cdr.Folder),
    Work(10, Cdr.Work),
    File(20, Cdr.FileObject),
    Binary(30, Fcrepo4Repository.Binary),
    DepositRecord(100, Cdr.DepositRecord),
    ContentRoot(100, Cdr.ContentRoot);

    private int displayOrder;
    private String uri;
    private Resource resource;

    ResourceType(int displayOrder, Resource resource) {
        this.displayOrder = displayOrder;
        this.resource = resource;
        this.uri = resource.getURI();
    }

    public int getDisplayOrder() {
        return this.displayOrder;
    }

    /**
     * @return the resource type as a String URI
     */
    public String getUri() {
        return this.uri;
    }

    /**
     * @return the resource type represented as an RDF resource
     */
    public Resource getResource() {
        return resource;
    }

    public boolean equals(String name) {
        return this.name().equals(name);
    }

    public static ResourceType getResourceTypeByUri(String uri) {
        for (ResourceType type : values()) {
            if (type.uri.equals(uri)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Gets the resourceType for rdf type uris
     *
     * @param uris
     * @return
     */
    public static ResourceType getResourceTypeForUris(List<String> uris) {
        for (String uri : uris) {
            ResourceType type = getResourceTypeByUri(uri);
            if (type != null) {
                return type;
            }
        }
        return null;
    }
}
