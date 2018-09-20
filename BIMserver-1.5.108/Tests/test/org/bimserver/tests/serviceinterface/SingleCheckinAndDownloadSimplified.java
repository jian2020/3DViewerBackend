package org.bimserver.tests.serviceinterface;

/******************************************************************************
 * Copyright (C) 2009-2018  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.apache.commons.io.IOUtils;
import org.bimserver.database.queries.om.DefaultQueries;
import org.bimserver.interfaces.objects.SDeserializerPluginConfiguration;
import org.bimserver.interfaces.objects.SProject;
import org.bimserver.interfaces.objects.SSerializerPluginConfiguration;
import org.bimserver.plugins.services.BimServerClientInterface;
import org.bimserver.plugins.services.Flow;
import org.bimserver.shared.UsernamePasswordAuthenticationInfo;
import org.bimserver.test.TestWithEmbeddedServer;
import org.junit.Test;

public class SingleCheckinAndDownloadSimplified extends TestWithEmbeddedServer {

	@Test
	public void test() {
		try {
			// Create a new BimServerClient with authentication
			BimServerClientInterface bimServerClient = getFactory().create(new UsernamePasswordAuthenticationInfo("admin@bimserver.org", "admin"));

			// Create a new project
			SProject newProject = bimServerClient.getServiceInterface().addProject("test" + Math.random(), "ifc2x3tc1");
			
			// This is the file we will be checking in
			Path ifcFile = Paths.get("../TestData/data/AC11-FZK-Haus-IFC.ifc");
			
			// Find a deserializer to use
			SDeserializerPluginConfiguration deserializer = bimServerClient.getServiceInterface().getSuggestedDeserializerForExtension("ifc", newProject.getOid());
			
			// Checkin
			bimServerClient.checkin(newProject.getOid(), "test", deserializer.getOid(), false, Flow.SYNC, ifcFile);
			
			// Find a serializer
			SSerializerPluginConfiguration colladaSerializer = bimServerClient.getServiceInterface().getSerializerByContentType("application/collada");
			
			// Get the project details
			newProject = bimServerClient.getServiceInterface().getProjectByPoid(newProject.getOid());
			
			// Download the latest revision  (the one we just checked in)
			Long topicIdId = bimServerClient.getServiceInterface().download(Collections.singleton(newProject.getLastRevisionId()), DefaultQueries.allAsString(), colladaSerializer.getOid(), false); // Note: sync: false
			InputStream downloadData = bimServerClient.getDownloadData(topicIdId);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(downloadData, baos);
			System.out.println(baos.size() + " bytes downloaded");
		} catch (Exception e) {
			fail(e.getMessage());
		}
	}
}