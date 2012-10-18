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
package edu.unc.lib.dl.search.solr.util;

import java.util.List;

import edu.unc.lib.dl.util.ContentModelHelper;

public enum ResourceType {
	Collection(1), Aggregate(3), Folder(2), Item(3);
	
	private int displayOrder;
	
	ResourceType(int displayOrder) {
		this.displayOrder = displayOrder;
	}
	
	public int getDisplayOrder(){
		return this.displayOrder;
	}
	
	public boolean equals(String name) {
		return this.name().equals(name);
	}
	
	public static ResourceType getResourceTypeByContentModels(List<String> contentModels) {
		if (contentModels.contains(ContentModelHelper.Model.COLLECTION.getPID().getURI())) {
			return Collection;
		}
		if (contentModels.contains(ContentModelHelper.Model.AGGREGATE_WORK.getPID().getURI())) {
			return Aggregate;
		}
		if (contentModels.contains(ContentModelHelper.Model.CONTAINER.getPID().getURI())) {
			return Folder;
		}
		if (contentModels.contains(ContentModelHelper.Model.SIMPLE.getPID().getURI())) {
			return Item;
		}
		return null;
	}
}
