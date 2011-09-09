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
package edu.unc.lib.dl.data.ingest.solr;

import java.util.concurrent.atomic.AtomicInteger;

public class CountDownUpdateRequest extends SolrUpdateRequest {
	
	AtomicInteger blockCount;
	
	public CountDownUpdateRequest(String pid, SolrUpdateAction action){
		super(pid, action);
		this.blockCount = new AtomicInteger(0);
	}
	
	public CountDownUpdateRequest(String pid, SolrUpdateAction action, SolrUpdateRequest linkedRequest){
		super(pid, action, linkedRequest);
		this.blockCount = new AtomicInteger(0);
	}
	
	@Override
	public void linkedRequestEstablished(SolrUpdateRequest linkerRequest){
		blockCount.incrementAndGet();
	}
	
	@Override
	public void linkedRequestCompleted(SolrUpdateRequest completedRequest){
		blockCount.decrementAndGet();
	}
	
	@Override
	public boolean isBlocked(){
		if (blockCount.get() > 0)
			return true;
		return false;
	}
}
