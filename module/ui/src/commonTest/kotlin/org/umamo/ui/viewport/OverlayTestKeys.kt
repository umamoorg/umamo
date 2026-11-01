package org.umamo.ui.viewport

import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner

/** The drawable a test's overlay mesh belongs to; the producers these tests read build drawable meshes alone. */
internal val MeshOverlayMesh.drawableOwnerId: DrawableId get() = (owner as ElementOwner.Drawable).id