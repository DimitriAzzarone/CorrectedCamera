package ch.formazione.correctedcamera
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
class FrameBufferTest {
 @Test fun rgbaConversionHasExplicitChannelOrderAndAlpha() {
  assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0x80112233.toInt()),FrameBuffer.argb(byteArrayOf(-1,0,0,-1,0,-1,0,-1,0x11,0x22,0x33,0x80.toByte())))
 }

 @Test fun noPaddingPreservesPixelsAndOriginalPosition() {
  val input=ByteBuffer.wrap(ByteArray(16){it.toByte()})
  assertArrayEquals(input.array(),FrameBuffer.rgba(input,2,2,8,4));assertEquals(0,input.position())
 }
 @Test fun finalRowCanOmitPadding() {
  val input=ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12,13,14,15,16))
  assertArrayEquals(ByteArray(16){(it+1).toByte()},FrameBuffer.rgba(input,2,2,12,4))
 }
 @Test fun pixelStrideAndNonzeroPositionAreHandled() {
  val b=ByteBuffer.wrap(byteArrayOf(99,99,1,2,3,4,88,88,5,6,7,8));b.position(2)
  assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8),FrameBuffer.rgba(b,2,1,12,6));assertEquals(2,b.position())
 }
 @Test(expected=IllegalArgumentException::class) fun truncatedImageRejected() {FrameBuffer.rgba(ByteBuffer.allocate(10),2,2,8,4)}
 @Test fun backlogKeepsOnlyLatestFrame() {
  val queue=LatestFrameSlot<Int>();for(i in 0..1000) {val old=queue.offer(i);if(i>0)assertEquals(i-1,old)}
  assertEquals(1000,queue.take());assertNull(queue.take())
 }
 @Test fun pacingDropsFramesBeforeBitmapWork() {
  val p=FramePacer();assertTrue(p.accept(0));assertFalse(p.accept(20));assertFalse(p.accept(66));assertTrue(p.accept(67));assertTrue(p.accept(134))
 }
}
