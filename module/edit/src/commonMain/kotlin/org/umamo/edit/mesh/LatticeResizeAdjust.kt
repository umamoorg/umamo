package org.umamo.edit.mesh

import org.umamo.edit.OperatorParameter

/*
 * A lattice resize's face on the operation settings strip: the new resolution as a ratio-locked pair of rows, either
 * the cell counts a typed size asked for or the factors a subdivision split every cell by.  The keys are declared
 * here, in the module that commits the resize, so the UI's label table names constants rather than repeating strings.
 */

/** The largest number of cells a lattice row or column takes from a typed size or a subdivision. */
const val LATTICE_DIVISION_MAX = 100

/** The largest factor a subdivision splits a lattice's cells by along one axis. */
const val LATTICE_SPLIT_MAX = 10

/** The parameter keys a lattice resize's rows carry. */
object LatticeResizeParameterKeys {
	/** The Rows row: the cell count along v a typed size asked for. */
	const val ROWS = "lattice.rows"

	/** The Columns row: the cell count along u. */
	const val COLUMNS = "lattice.columns"

	/** The Row Splits row: the factor a subdivision split every row of cells by. */
	const val ROW_SPLITS = "lattice.rowSplits"

	/** The Column Splits row: the factor along u. */
	const val COLUMN_SPLITS = "lattice.columnSplits"
}

/**
 * The strip's rows for a lattice resized to [rows] x [columns] cells: the two counts, ratio-paired.
 *
 * @param Int rows The cell count along v.
 * @param Int columns The cell count along u.
 * @return List The two rows.
 */
fun latticeSizeParameters(rows: Int, columns: Int): List<OperatorParameter> =
	listOf(
		OperatorParameter.IntParameter(LatticeResizeParameterKeys.ROWS, LatticeResizeParameterKeys.ROWS, rows, 1, LATTICE_DIVISION_MAX, ratioPartnerKey = LatticeResizeParameterKeys.COLUMNS),
		OperatorParameter.IntParameter(LatticeResizeParameterKeys.COLUMNS, LatticeResizeParameterKeys.COLUMNS, columns, 1, LATTICE_DIVISION_MAX),
	)

/**
 * The strip's rows for a subdivision by [rowSplits] x [columnSplits]: the two factors, ratio-paired.
 *
 * @param Int rowSplits The factor every row of cells was split by.
 * @param Int columnSplits The factor every column of cells was split by.
 * @return List The two rows.
 */
fun latticeSplitParameters(rowSplits: Int, columnSplits: Int): List<OperatorParameter> =
	listOf(
		OperatorParameter.IntParameter(LatticeResizeParameterKeys.ROW_SPLITS, LatticeResizeParameterKeys.ROW_SPLITS, rowSplits, 1, LATTICE_SPLIT_MAX, ratioPartnerKey = LatticeResizeParameterKeys.COLUMN_SPLITS),
		OperatorParameter.IntParameter(LatticeResizeParameterKeys.COLUMN_SPLITS, LatticeResizeParameterKeys.COLUMN_SPLITS, columnSplits, 1, LATTICE_SPLIT_MAX),
	)