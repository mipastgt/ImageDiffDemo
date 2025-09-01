package de.mpmediasoft.imagediff

import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

class ParallelDiffHandlerTest {

    @OptIn(ExperimentalTime::class)
    @Test
    fun diffOfRawArgbImageDataTest3() {
        runBlocking {
            val refFile = "../data/VP00150-new.png"
            val cmpFile = "../data/VP00150-old.png"

            val refImage = imageToArgbIntImage(File(refFile))
            val cmpImage = imageToArgbIntImage(File(cmpFile))
            val diffImage = BufferedImage(refImage.width, refImage.height, BufferedImage.TYPE_INT_ARGB)
            val tgtImage = BufferedImage(refImage.width, refImage.height, BufferedImage.TYPE_INT_ARGB)

            val xOffset = -7
            val yOffset = 7

            // Reference diff to test against.
            _sequentialDiffOfRawArgbIntImageData(
                (refImage.raster.dataBuffer as DataBufferInt).data, refImage.width, refImage.height,
                (diffImage.raster.dataBuffer as DataBufferInt).data, // same width and height as refImage
                (cmpImage.raster.dataBuffer as DataBufferInt).data, cmpImage.width, cmpImage.height,
                xOffset, yOffset,
                DiffOp.SQR_DIFF_RED
            )

            val numLoops = 100
            val elapsed = measureTime {
                for (i in 1..numLoops) {
                    //                _sequentialDiffOfRawArgbIntImageData(
                    //                _simpleParallelDiffOfRawArgbIntImageData(
                    _chunkedParallelDiffOfRawArgbIntImageData(
                        (refImage.raster.dataBuffer as DataBufferInt).data, refImage.width, refImage.height,
                        (tgtImage.raster.dataBuffer as DataBufferInt).data, // same width and height as refImage
                        (cmpImage.raster.dataBuffer as DataBufferInt).data, cmpImage.width, cmpImage.height,
                        xOffset, yOffset,
                        DiffOp.SQR_DIFF_RED
                    )
                }
            }
            println("Diff computed in ${elapsed / numLoops}")

            assertContentEquals(
                (diffImage.raster.dataBuffer as DataBufferInt).data,
                (tgtImage.raster.dataBuffer as DataBufferInt).data
            )

            tgtImage.let { ImageIO.write(it, "png", File("../testresults/VP00150-computed-diff.png")) }
        }
    }

//                           mac-mini X86                   mac-mini AARCH64 (M2)   macbook pro AARCH64 (M4)
// Single-threaded         : Diff computed in 36.511684ms   13.940397ms             10.962306ms
// Multi-threaded (simple) : Diff computed in 12.681792ms    5.277718ms              3.588887ms
// Multi-threaded (chunks) : Diff computed in 10.516840ms    3.349768ms              2.173872ms

    private fun imageToArgbIntImage(imageFile: File): BufferedImage {
        val bufImg: BufferedImage = ImageIO.read(imageFile)
        return if (bufImg.type == BufferedImage.TYPE_INT_ARGB) {
            bufImg
        } else {
            val convertedImg = BufferedImage(bufImg.width, bufImg.height, BufferedImage.TYPE_INT_ARGB)
            convertedImg.graphics.let { g ->
                g.drawImage(bufImg, 0, 0, null)
                g.dispose()
            }
            convertedImg
        }
    }

}