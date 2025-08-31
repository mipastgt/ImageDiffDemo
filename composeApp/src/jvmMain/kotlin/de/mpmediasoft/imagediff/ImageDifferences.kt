package de.mpmediasoft.imagediff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.math.min

enum class DiffOp {OVERWRITE, TRANSPARENT_WHITE, SQR_DIFF_RED}

suspend fun diffOfRawArgbIntImageData(
    refImage: IntArray, refImageWidth: Int, refImageHeight: Int,
    tgtImage: IntArray, // same width and height as refImage
    cmpImage: IntArray, cmpImageWidth: Int, cmpImageHeight: Int,
    xOffset: Int, yOffset: Int,
    diffOp: DiffOp
) {
    _chunkedParallelDiffOfRawArgbIntImageData(
        refImage, refImageWidth, refImageHeight,
        tgtImage,
        cmpImage, cmpImageWidth, cmpImageHeight,
        xOffset, yOffset,
        diffOp
    )
}

suspend fun normDiffValueOfRawArgbIntImageData(
    refImage: IntArray, refImageWidth: Int, refImageHeight: Int,
    cmpImage: IntArray, cmpImageWidth: Int, cmpImageHeight: Int,
    xOffset: Int, yOffset: Int
): Double {
    return _chunkedParallelDiffOfRawArgbIntImageData(
        refImage, refImageWidth, refImageHeight,
        null,
        cmpImage, cmpImageWidth, cmpImageHeight,
        xOffset, yOffset,
        DiffOp.SQR_DIFF_RED
    )
}

// Internal implementation:

internal data class _IndexRange (val x: IntRange, val y: IntRange) {
    fun isEmpty(): Boolean = x.isEmpty() || y.isEmpty()
    fun isNotEmpty(): Boolean = ! isEmpty()
    val sizeX
        get() = x.last - x.first + 1
    val sizeY
        get() = y.last - y.first + 1
}

internal fun _imageOverlapIndexRange(
    refImageWidth: Int, refImageHeight: Int,
    cmpImageWidth: Int, cmpImageHeight: Int,
    xOffset: Int, yOffset: Int
): _IndexRange {
    val xMin = max(0, xOffset)
    val yMin = max(0, yOffset)
    val xMax = min(refImageWidth, xOffset + cmpImageWidth) - 1
    val yMax = min(refImageHeight, yOffset + cmpImageHeight) - 1

    val xRange: IntRange = xMin..xMax
    val yRange: IntRange = yMin..yMax

    return _IndexRange(xRange, yRange)
}

// ARGB Ints:

internal fun _intArgbDiff(refImage: IntArray, tgtImage: IntArray?, cmpImage: IntArray, refIndex: Int, cmpIndex: Int, diffOp: DiffOp): Double {
    val refArgbVal = refImage[refIndex]
    val refA = ((refArgbVal shr 24) and 0xFF)
    val refR = ((refArgbVal shr 16) and 0xFF)
    val refG = ((refArgbVal shr  8) and 0xFF)
    val refB = ((refArgbVal shr  0) and 0xFF)

    val cmpArgbVal = cmpImage[cmpIndex]
//    val cmpA = ((cmpArgbVal shr 24) and 0xFF) // Currently not used.
    val cmpR = ((cmpArgbVal shr 16) and 0xFF)
    val cmpG = ((cmpArgbVal shr  8) and 0xFF)
    val cmpB = ((cmpArgbVal shr  0) and 0xFF)

    val tgtA = refA
    var tgtR = refR
    var tgtG = refG
    var tgtB = refB

    var normDiff = 0.0

    when (diffOp) {
        DiffOp.OVERWRITE -> {
            tgtR = cmpR
            tgtG = cmpG
            tgtB = cmpB
        }
        DiffOp.TRANSPARENT_WHITE -> {
            if (cmpR != 0xFF || cmpG != 0xFF || cmpB != 0xFF) {
                tgtR = cmpR
                tgtG = cmpG
                tgtB = cmpB
            }
        }
        DiffOp.SQR_DIFF_RED -> {
            val diffR = refR - cmpR
            val diffG = refG - cmpG
            val diffB = refB - cmpB

            val totDiffSq = diffR*diffR + diffG*diffG + diffB*diffB

            val D = totDiffSq/768
            normDiff = totDiffSq.toDouble()/768.0

            tgtR = 0xFF
            tgtG = 0xFF - D
            tgtB = 0xFF - D
        }
    }

    if (tgtImage != null) {
        tgtImage[refIndex] = (
                ((0xFF and tgtA) shl 24) or
                ((0xFF and tgtR) shl 16) or
                ((0xFF and tgtG) shl 8) or
                ((0xFF and tgtB) shl 0)
        )
    }

    return normDiff
}

internal suspend fun _sequentialDiffOfRawArgbIntImageData(
    refImage: IntArray, refImageWidth: Int, refImageHeight: Int,
    tgtImage: IntArray?, // same width and height as refImage
    cmpImage: IntArray, cmpImageWidth: Int, cmpImageHeight: Int,
    xOffset: Int, yOffset: Int,
    diffOp: DiffOp
): Double {
    require(tgtImage == null || refImage.size == tgtImage.size)

    val ir = _imageOverlapIndexRange(refImageWidth, refImageHeight, cmpImageWidth, cmpImageHeight, xOffset, yOffset)

    var sumDiff = 0.0
    val counter = (ir.x.last - ir.x.first + 1).toLong() * (ir.y.last - ir.y.first + 1).toLong()

    for (iy in ir.y) {
        for (ix in ir.x) {
            val cix = ix - xOffset
            val ciy = iy - yOffset
            val refIndex = iy * refImageWidth + ix
            val cmpIndex = ciy * cmpImageWidth + cix
            sumDiff += _intArgbDiff(refImage, tgtImage, cmpImage, refIndex, cmpIndex, diffOp)
        }
    }

    return sumDiff/counter
}

@OptIn(ExperimentalAtomicApi::class)
internal suspend fun _simpleParallelDiffOfRawArgbIntImageData(
    refImage: IntArray, refImageWidth: Int, refImageHeight: Int,
    tgtImage: IntArray?, // same width and height as refImage
    cmpImage: IntArray, cmpImageWidth: Int, cmpImageHeight: Int,
    xOffset: Int, yOffset: Int,
    diffOp: DiffOp
): Double {
    require(tgtImage == null || refImage.size == tgtImage.size)

    val ir = _imageOverlapIndexRange(refImageWidth, refImageHeight, cmpImageWidth, cmpImageHeight, xOffset, yOffset)

    val rowDiffArgs = List(ir.sizeY) { ir.y.first + it }

    fun rowDiff(iy: Int): Double {
        var partialSumDiff = 0.0
        for (ix in ir.x) {
            val cix = ix - xOffset
            val ciy = iy - yOffset
            val refIndex = iy * refImageWidth + ix
            val cmpIndex = ciy * cmpImageWidth + cix
            partialSumDiff += _intArgbDiff(refImage, tgtImage, cmpImage, refIndex, cmpIndex, diffOp)
        }
        return partialSumDiff
    }

    val counter = (ir.x.last - ir.x.first + 1).toLong() * (ir.y.last - ir.y.first + 1).toLong()
    val partialSumDiffs = DoubleArray(rowDiffArgs.size)

    coroutineScope {
        withContext(Dispatchers.Default) {
            rowDiffArgs.forEachIndexed { i, y ->
                launch {
                    partialSumDiffs[i] = rowDiff(y)
                }
            }
        }
    }

    return partialSumDiffs.sum()/counter
}

internal suspend fun _chunkedParallelDiffOfRawArgbIntImageData(
    refImage: IntArray, refImageWidth: Int, refImageHeight: Int,
    tgtImage: IntArray?, // same width and height as refImage
    cmpImage: IntArray, cmpImageWidth: Int, cmpImageHeight: Int,
    xOffset: Int, yOffset: Int,
    diffOp: DiffOp
): Double {
    require(tgtImage == null || refImage.size == tgtImage.size)

    val ir = _imageOverlapIndexRange(refImageWidth, refImageHeight, cmpImageWidth, cmpImageHeight, xOffset, yOffset)
    val numY = ir.y.last - ir.y.first + 1
    if (numY > 0) {
        val numChunks = min(128, numY)
        val chunkSize = numY / numChunks
        val chunkRange = 0..numChunks - 1
        val chunks = mutableListOf<IntRange>()
        var first: Int = ir.y.first
        var last: Int
        for (c in chunkRange) {
            last = if (c < chunkRange.last) first + chunkSize - 1 else ir.y.last
            chunks.add(first..last)
            first = last + 1
        }

        val counter = (ir.x.last - ir.x.first + 1).toLong() * (ir.y.last - ir.y.first + 1).toLong()
        val partialSumDiffs = DoubleArray(chunks.size)

        coroutineScope {
            withContext(Dispatchers.Default) {
                chunks.forEachIndexed { i, chunk ->
                    launch {
                        var partialSumDiff = 0.0
                        for (iy in chunk) {
                            for (ix in ir.x) {
                                val cix = ix - xOffset
                                val ciy = iy - yOffset
                                val refIndex = iy * refImageWidth + ix
                                val cmpIndex = ciy * cmpImageWidth + cix
                                partialSumDiff += _intArgbDiff(refImage, tgtImage, cmpImage, refIndex, cmpIndex, diffOp)
                            }
                        }
                        partialSumDiffs[i] = partialSumDiff
                    }
                }
            }
        }

        return partialSumDiffs.sum()/counter
    }

    return Double.MAX_VALUE
}
