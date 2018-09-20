package org.bimserver.tests.lowlevel;

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

import java.net.URL;
import java.nio.file.Paths;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.interfaces.objects.SDeserializerPluginConfiguration;
import org.bimserver.interfaces.objects.SProject;
import org.bimserver.interfaces.objects.SSerializerPluginConfiguration;
import org.bimserver.models.ifc2x3tc1.IfcRoot;
import org.bimserver.plugins.services.BimServerClientInterface;
import org.bimserver.plugins.services.Flow;
import org.bimserver.shared.UsernamePasswordAuthenticationInfo;
import org.bimserver.shared.interfaces.LowLevelInterface;
import org.bimserver.test.TestWithEmbeddedServer;
import org.junit.Test;

public class TestSetString extends TestWithEmbeddedServer {

	@Test
	public void test() {
		try {
			BimServerClientInterface bimServerClient = getFactory().create(new UsernamePasswordAuthenticationInfo("admin@bimserver.org", "admin"));
			bimServerClient.getSettingsInterface().setCacheOutputFiles(false);
			LowLevelInterface lowLevelInterface = bimServerClient.getLowLevelInterface();
			
			SProject newProject = bimServerClient.getServiceInterface().addProject("test" + Math.random(), "ifc2x3tc1");
			
			SDeserializerPluginConfiguration suggestedDeserializerForExtension = bimServerClient.getServiceInterface().getSuggestedDeserializerForExtension("ifc", newProject.getOid());
			bimServerClient.checkin(newProject.getOid(), "initial", suggestedDeserializerForExtension.getOid(), false, Flow.SYNC, new URL("https://github.com/opensourceBIM/TestFiles/raw/master/TestData/data/WallOnly.ifc"));
			newProject = bimServerClient.getServiceInterface().getProjectByPoid(newProject.getOid());
			
			SSerializerPluginConfiguration serializer = bimServerClient.getServiceInterface().getSerializerByName("Ifc2x3tc1 (Streaming)");
			
			bimServerClient.download(newProject.getLastRevisionId(), serializer.getOid(), Paths.get("test1.ifc"));

			IfcModelInterface model = bimServerClient.getModel(newProject, newProject.getLastRevisionId(), true, false);
			long tid = lowLevelInterface.startTransaction(newProject.getOid());
			
			IfcRoot wall = (IfcRoot) model.getByGuid("3Ep4r0uuX5ywPYOUG2H2A4");
			
			bimServerClient.getLowLevelInterface().setStringAttribute(tid, wall.getOid(), "Name", "Test 12345");
			bimServerClient.getLowLevelInterface().setStringAttribute(tid, wall.getOid(), "GlobalId", "NEW GUID");
			
			long roid = lowLevelInterface.commitTransaction(tid, "v2");
			
			bimServerClient.download(roid, serializer.getOid(), Paths.get("test3.ifc"));
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}
}