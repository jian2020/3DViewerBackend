package org.bimserver.geometry.accellerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.bimserver.BimServer;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.database.DatabaseSession;
import org.bimserver.database.OldQuery;
import org.bimserver.database.queries.QueryObjectProvider;
import org.bimserver.database.queries.om.Include;
import org.bimserver.database.queries.om.Query;
import org.bimserver.database.queries.om.QueryException;
import org.bimserver.database.queries.om.QueryPart;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.interfaces.objects.SDensity;
import org.bimserver.models.geometry.GeometryPackage;
import org.bimserver.models.store.Density;
import org.bimserver.models.store.Revision;
import org.bimserver.shared.AbstractHashMapVirtualObject;
import org.bimserver.shared.HashMapVirtualObject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class GeometryAccellerator {
	private static final Logger LOGGER = LoggerFactory.getLogger(GeometryAccellerator.class);
	private final BimServer bimServer;
	private final LoadingCache<OctreeKey, Octree<GeometryObject>> octrees;
	private final LoadingCache<DensityThresholdKey, DensityThreshold> densityThresholds;
	private final LoadingCache<ReuseKey, ReuseSet> reuseSets;
	private Path geometryCacheFolder;

	public GeometryAccellerator(BimServer bimServer) {
		this.bimServer = bimServer;
		
		geometryCacheFolder = this.bimServer.getHomeDir().resolve("geometrycache");

		octrees = CacheBuilder.newBuilder().maximumSize(10000).build(new CacheLoader<OctreeKey, Octree<GeometryObject>>() {
			public Octree<GeometryObject> load(OctreeKey key) {
				return generateOctree(key);
			}
		});
		
		reuseSets = CacheBuilder.newBuilder().maximumSize(10000).build(new CacheLoader<ReuseKey, ReuseSet>() {
			public ReuseSet load(ReuseKey key) {
				return generateReuseSet(key);
			}
		});
		
		densityThresholds = CacheBuilder.newBuilder().maximumSize(10000).build(new CacheLoader<DensityThresholdKey, DensityThreshold>() {
			@Override
			public DensityThreshold load(DensityThresholdKey key) throws Exception {
				return generateDensityThreshold(key);
			}
		});
	}

	public Octree<GeometryObject> getOctree(Set<Long> roids, Set<String> excludedClasses, Set<Long> geometryIdsToReuse, int maxDepth, float minimumThreshold, float maximumThreshold) {
		OctreeKey key = new OctreeKey(roids, excludedClasses, geometryIdsToReuse, maxDepth, minimumThreshold, maximumThreshold);
		try {
			return octrees.get(key);
		} catch (ExecutionException e) {
			LOGGER.error("", e);
			return null;
		}
	}

	private Octree<GeometryObject> generateOctree(OctreeKey key) {
		try (DatabaseSession databaseSession = bimServer.getDatabase().createSession()) {
			org.bimserver.database.queries.Bounds totalBounds = new org.bimserver.database.queries.Bounds();

			// TODO not taking into account a density here, so potentially, this
			// will come back with too many tiles with > 0 object, not really a
			// problem though

			for (long roid : key.getRoids()) {
				Revision revision = databaseSession.get(roid, OldQuery.getDefault());
				totalBounds.integrate(revision.getBoundsMm());
			}

			Octree<GeometryObject> octree = new Octree<>(totalBounds, key.getMaxDepth());

			// Assuming all given roids are of projects that all have the same
			// schema
			Revision revision = databaseSession.get(key.getRoids().iterator().next(), OldQuery.getDefault());
			PackageMetaData packageMetaData = bimServer.getMetaDataManager().getPackageMetaData(revision.getProject().getSchema());

			Set<EClass> excluded = new HashSet<>();
			if (key.getExcludedClasses() != null) {
				for (String exclude : key.getExcludedClasses()) {
					excluded.add(packageMetaData.getEClass(exclude));
				}
			}

			Query query = new Query(packageMetaData);

			QueryPart queryPart = query.createQueryPart();
			queryPart.addType(packageMetaData.getEClass("IfcProduct"), true, excluded);

			Include product = queryPart.createInclude();
			product.addType(packageMetaData.getEClass("IfcProduct"), true);
			product.addFieldDirect("geometry");
			Include geometryInfo = product.createInclude();
			geometryInfo.addType(GeometryPackage.eINSTANCE.getGeometryInfo(), false);
			geometryInfo.addFieldDirect("data");
			geometryInfo.addFieldDirect("boundsMm");
			Include boundsInclude = geometryInfo.createInclude();
			boundsInclude.addType(GeometryPackage.eINSTANCE.getBounds(), false);
			boundsInclude.addFieldDirect("min");
			boundsInclude.addFieldDirect("max");
			Include dataInclude = geometryInfo.createInclude();
			dataInclude.addType(GeometryPackage.eINSTANCE.getGeometryData(), false);
			dataInclude.addFieldDirect("boundsMm");
			Include dataBoundsInclude = dataInclude.createInclude();
			dataBoundsInclude.addType(GeometryPackage.eINSTANCE.getBounds(), false);
			dataBoundsInclude.addFieldDirect("min");
			dataBoundsInclude.addFieldDirect("max");

			QueryObjectProvider queryObjectProvider = new QueryObjectProvider(databaseSession, bimServer, query, key.getRoids(), packageMetaData);
			HashMapVirtualObject next = queryObjectProvider.next();
			while (next != null) {
				AbstractHashMapVirtualObject geometry = next.getDirectFeature(packageMetaData.getEReference("IfcProduct", "geometry"));
				if (geometry != null) {
					float density = (float) geometry.get("density");
					if (density >= key.getMinimumThreshold()) {
						long geometryDataId = (long) geometry.get("data");
						AbstractHashMapVirtualObject boundsMm = geometry.getDirectFeature(GeometryPackage.eINSTANCE.getGeometryInfo_BoundsMm());
						if (key.getGeometryIdsToReuse().contains(geometryDataId)) {
							// Special case, we now have to use the complete
							// bounding box of all reused objects, instead of using
							// the object's aabb
							AbstractHashMapVirtualObject geometryData = geometry.getDirectFeature(GeometryPackage.eINSTANCE.getGeometryInfo_Data());
							boundsMm = geometryData.getDirectFeature(GeometryPackage.eINSTANCE.getGeometryData_BoundsMm());
						}
						if (boundsMm != null) {
							AbstractHashMapVirtualObject min = boundsMm.getDirectFeature(GeometryPackage.eINSTANCE.getBounds_Min());
							AbstractHashMapVirtualObject max = boundsMm.getDirectFeature(GeometryPackage.eINSTANCE.getBounds_Max());
	
							AbstractHashMapVirtualObject geometryData = geometry.getDirectFeature(GeometryPackage.eINSTANCE.getGeometryInfo_Data());
							int saveableTriangles = (int)geometryData.get("saveableTriangles");
							
							org.bimserver.database.queries.Bounds objectBounds = new org.bimserver.database.queries.Bounds((double) min.get("x"), (double) min.get("y"), (double) min.get("z"), (double) max.get("x"), (double) max.get("y"),
									(double) max.get("z"));
							octree.add(new GeometryObject(next.getOid(), next.getRoid(), saveableTriangles, density), objectBounds);
						}
					}
				}
				next = queryObjectProvider.next();
			}
			
			octree.traverseBreathFirst(new Traverser<GeometryObject>() {
				@Override
				public void traverse(Node<GeometryObject> t) {
					t.sort();
				}
			});
			
			return octree;
		} catch (BimserverDatabaseException e) {
			LOGGER.error("", e);
		} catch (IOException e) {
			LOGGER.error("", e);
		} catch (QueryException e) {
			LOGGER.error("", e);
		}
		return null;
	}

	private DensityThreshold generateDensityThreshold(DensityThresholdKey key) {
		DensityThreshold densityThreshold = new DensityThreshold();
		try (DatabaseSession session = bimServer.getDatabase().createSession()) {
			Revision revision = session.get(key.getRoid(), OldQuery.getDefault());
			EList<Density> densities = revision.getDensityCollection().getDensities();
			long cumulativeTrianglesBelow = 0;
			long cumulativeTrianglesAbove = 0;
			Density densityResult = null;
			int l = 0;
			float ld = -1f;
			for (Density density : densities) {
				if (key.getExcludedTypes().contains(density.getType())) {
					continue;
				}
				if (l == 0) {
					cumulativeTrianglesBelow += density.getTrianglesBelow(); // Not a typo
					densityResult = density;
					if (cumulativeTrianglesBelow > key.getNrTriangles()) {
						l = 1;
						ld = density.getDensity();
					}
				} else if (l == 1) {
					if (ld == density.getDensity()) {
						cumulativeTrianglesBelow += density.getTrianglesBelow();
						densityResult = density;
					} else {
						cumulativeTrianglesAbove += density.getTrianglesBelow(); // Not a typo
					}
				}
			}
			if (densityResult == null) {
				densityResult = densities.get(0);
			}
			// This is useful information, so the client knows exactly how many triangles will be loaded by using this threshold
			densityResult.setTrianglesBelow(cumulativeTrianglesBelow);
			densityResult.setTrianglesAbove(cumulativeTrianglesAbove);
			densityThreshold.setDensity(bimServer.getSConverter().convertToSObject(densityResult));
		} catch (BimserverDatabaseException e) {
			LOGGER.error("", e);
		}
		return densityThreshold;
	}
	
	public Set<Long> getGeometryDataToReuse(Set<Long> roids, Set<String> excludedTypes, Integer trianglesToSave) {
		ReuseKey key = new ReuseKey(roids, excludedTypes);
		try {
			return reuseSets.get(key).getListOfGeometryDataIds(trianglesToSave);
		} catch (ExecutionException e) {
			LOGGER.error("", e);
			return null;
		}
	}

	private ReuseSet generateReuseSet(ReuseKey key) {
		ReuseSet reuseSet = new ReuseSet();
		try (DatabaseSession databaseSession = bimServer.getDatabase().createSession()) {
			// Assuming all given roids are of projects that all have the same schema

			Revision revision = databaseSession.get(key.getRoids().iterator().next(), OldQuery.getDefault());
			PackageMetaData packageMetaData = bimServer.getMetaDataManager().getPackageMetaData(revision.getProject().getSchema());

			Query query = new Query(packageMetaData);

			Set<EClass> excluded = new HashSet<>();
			for (String exclude : key.getExcludedClasses()) {
				excluded.add(packageMetaData.getEClass(exclude));
			}

			QueryPart queryPart = query.createQueryPart();
			queryPart.addType(packageMetaData.getEClass("IfcProduct"), true, excluded);

			Include product = queryPart.createInclude();
			product.addType(packageMetaData.getEClass("IfcProduct"), true);
			product.addFieldDirect("geometry");
			Include geometryInfo = product.createInclude();
			geometryInfo.addType(GeometryPackage.eINSTANCE.getGeometryInfo(), false);

			Map<Long, ReuseObject> map = new HashMap<>();
			
			QueryObjectProvider queryObjectProvider = new QueryObjectProvider(databaseSession, bimServer, query, key.getRoids(), packageMetaData);
			HashMapVirtualObject next = queryObjectProvider.next();
			while (next != null) {
				AbstractHashMapVirtualObject geometry = next.getDirectFeature(packageMetaData.getEReference("IfcProduct", "geometry"));
				if (geometry != null) {
					Long dataId = (Long) geometry.get("data");
					ReuseObject reuseObject = map.get(dataId);
					if (reuseObject == null) {
						reuseObject = new ReuseObject(dataId, 1, (int) geometry.get("primitiveCount"));
						map.put(dataId, reuseObject);
					} else {
						reuseObject.inc();
					}
				}
				next = queryObjectProvider.next();
			}
			for (long dataId : map.keySet()) {
				reuseSet.add(map.get(dataId));
			}
		} catch (BimserverDatabaseException e) {
			LOGGER.error("", e);
		} catch (QueryException e) {
			LOGGER.error("", e);
		} catch (IOException e) {
			LOGGER.error("", e);
		}
		return reuseSet;
	}

	public SDensity getDensityThreshold(Long roid, Long nrTriangles, Set<String> excludedTypes) {
		DensityThresholdKey key = new DensityThresholdKey(roid, nrTriangles, excludedTypes);
		try {
			return densityThresholds.get(key).getDensity();
		} catch (ExecutionException e) {
			LOGGER.error("", e);
			return null;
		}
	}
}