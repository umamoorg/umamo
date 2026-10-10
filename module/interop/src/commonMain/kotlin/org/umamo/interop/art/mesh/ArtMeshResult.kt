package org.umamo.interop.art.mesh

import org.umamo.geometry.mesh.PlanarTriangleMesh

/**
 * The art mesher's outcome.
 *
 * @property PlanarTriangleMesh? mesh       The mesh in the layer raster's pixels (y down, triangles with orient2d > 0), or null when none could be made - the notices say why.
 * @property List<ArtMeshNotice> notices    What the mesher adjusted or could not do.
 * @property ArtMeshStatistics?  statistics What went into the mesh; null when nothing was opaque.
 */
public class ArtMeshResult(
	public val mesh: PlanarTriangleMesh?,
	public val notices: List<ArtMeshNotice>,
	public val statistics: ArtMeshStatistics?,
)