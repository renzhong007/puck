package puck.linalg
package kernels

import org.bridj._
import com.nativelibs4java.opencl._
import java.nio._
import java.util

// TODO... i bet kernel has a context, so this leaks the context...
class CLMatrixBulkRangeCopy private(numBlocks: Int, blockSize: Int, kernel: CLKernel) {


  def bulkCopySrcRanges(dst: CLMatrix[Float], src: CLMatrix[Float], ranges: IndexedSeq[Range], events: CLEvent*)(implicit queue: CLQueue) = synchronized {
    assert(dst.cols == src.cols)
    val _ranges = {
     val rr = ranges.filter(_.nonEmpty)
     rr ++ Array.fill(rr.length % numBlocks)(0 until 0) 
   }
    val array = new Array[Int](_ranges.length * 3)
    var arrOff = 0;
    var dstOff = dst.offset
    for(r <- _ranges) {
      assert(r.step == 1)
      array(arrOff)   = src.offset + r.start
      array(arrOff+1) = r.length
      array(arrOff+2) = dstOff
      dstOff += r.length
      arrOff += 3
    }
    val ptr = Pointer.pointerToArray[java.lang.Integer](array)
    val intBuffer = queue.getContext.createIntBuffer(CLMem.Usage.InputOutput, array.length)
    val ev = intBuffer.write(queue, 0, array.length, ptr, false, events:_*)
    kernel.setArgs(dst.data, Integer.valueOf(dst.majorStride), intBuffer,
      src.data, Integer.valueOf(src.majorStride), Integer.valueOf(src.cols))
    val res = kernel.enqueueNDRange(queue, Array(numBlocks * blockSize, _ranges.length / numBlocks, 1), Array(numBlocks * blockSize, 1, 1), (ev +: events):_*)
    res.invokeUponCompletion(new Runnable() {
      def run() = { ptr.release(); intBuffer.release() }
    })
    res
  }

  def bulkCopyDstRanges(dst: CLMatrix[Float], ranges: IndexedSeq[Range], src: CLMatrix[Float],  events: CLEvent*)(implicit queue: CLQueue) = synchronized {
    assert(dst.cols == src.cols)
    val _ranges = {
     val rr = ranges.filter(_.nonEmpty)
     rr ++ Array.fill(rr.length % numBlocks)(0 until 0) 
   }
    val array = new Array[Int](_ranges.length * 3)
    var arrOff = 0;
    var srcOff = src.offset
    for(r <- _ranges) {
      assert(r.step == 1)
      array(arrOff)   = srcOff 
      array(arrOff+1) = r.length
      array(arrOff+2) = dst.offset + r.start
      srcOff += r.length
      arrOff += 3
    }
    val ptr = Pointer.pointerToArray[java.lang.Integer](array)
    val intBuffer = queue.getContext.createIntBuffer(CLMem.Usage.InputOutput, array.length)
    val ev = intBuffer.write(queue, 0, array.length, ptr, false, events:_*)
    kernel.setArgs(dst.data, Integer.valueOf(dst.majorStride), intBuffer,
      src.data, Integer.valueOf(src.majorStride), Integer.valueOf(src.cols))
    val res = kernel.enqueueNDRange(queue, Array(numBlocks * blockSize, _ranges.length / numBlocks, 1), Array(numBlocks * blockSize, 1, 1), (ev +: events):_*)
    res.invokeUponCompletion(new Runnable() {
      def run() = { ptr.release(); intBuffer.release() }
    })
    res
  }

}


object CLMatrixBulkRangeCopy {
  def apply(preferredBlockSize: Int = 16, maxNumBlocks: Int = 2)(implicit context: CLContext) = map.synchronized {
    import scala.collection.JavaConverters._
    // TODO ??!?!??!
    val x = context.getDevices.head.getMaxWorkItemSizes
    val size0 = x(0)
    // not sure what's going on, but intel reports 1024/1/1, but can't handle more than 1/1/1...
    val (numBlocks,blockSize) = if(size0 % preferredBlockSize != 0 || context.getDevices.head.toString.contains("Apple") && context.getDevices.head.toString.contains("Intel")) {
      (1,1)
    } else if(size0 / preferredBlockSize > maxNumBlocks) {
      (maxNumBlocks,preferredBlockSize)
    } else {
      (size0/preferredBlockSize toInt, preferredBlockSize toInt)
    }
    val prog = context.createProgram(program(numBlocks,blockSize))
    val kernel = prog.createKernel("bulk_copy")
    
    map.asScala.getOrElseUpdate(context, new CLMatrixBulkRangeCopy(numBlocks, blockSize, kernel))
  }

  private val map = new util.WeakHashMap[CLContext, CLMatrixBulkRangeCopy]

    def program(numBlocks: Int, blockSize: Int) = {
"""
#define NUM_BLOCKS """ + numBlocks + """
#define BLOCK_SIZE """ + blockSize + """
__kernel void bulk_copy(__global float* dst, int dstMajorStride, __global int* ptrs,
                        __global float* src, int srcMajorStride, int columns) {
                        
  int rowOffset = get_global_id(0);
  int ptrIndex = get_global_id(1);

  // do two blocks, one per half warp
  __local int indices[3 * NUM_BLOCKS];
  event_t event = async_work_group_copy(indices, ptrs + 3 * NUM_BLOCKS * ptrIndex, 3 * NUM_BLOCKS, 0);
  
  /*
  for(int i = rowOffset; i < 3 * NUM_BLOCKS; i += BLOCK_SIZE * NUM_BLOCKS) {
    indices[i] = ptrs[3 * NUM_BLOCKS * ptrIndex + i];
  }
  barrier(CLK_LOCAL_MEM_FENCE);
  */

  // todo: adjust for different number of blocks....
  int groupId = rowOffset / BLOCK_SIZE;
  int groupRow = rowOffset % BLOCK_SIZE;

  wait_group_events(1, &event);
  int srcOff = indices[groupId * 3 + 0]; 
  int length = indices[groupId * 3 + 1]; // number of rows to do total
  int dstOff = indices[groupId * 3 + 2];
  for (int column = 0; column < columns; ++column) {
    for(int row = groupRow; row < length; row += BLOCK_SIZE) {
       dst[column * dstMajorStride + dstOff + row] = src[column * srcMajorStride + srcOff + row];
    }
  }
}
"""
  }

/** Transposes src into dst, permuting the columns as it goes. Matrices are column major.*/
   def permuteTransposeCopy(blockSize: Int) = {
"""
#define BLOCK_SIZE """ + blockSize + """
__kernel void bulk_copy(__global float* dst, int dstMajorStride, 
                        __global float* src, int srcMajorStride, __global int* srcPtrs,
                        int srcRows, int srcCols) {
  // copy each col into block[i]
  __local block[BLOCK_SIZE][BLOCK_SIZE+1]; // + 1 to avoid bank conflicts
  event_t copyInEvents[BLOCK_SIZE];
                        
  int dstRow = get_global_id(0);
  int threadid = get_local_id(0);
  // dstRow - threadid is the same for all threads in a workgroup.
  __local myPtrs[BLOCK_SIZE];
  int ndo = min(BLOCK_SIZE, srcCols - (dstRow - threadid));
  barrier(CLK_LOCAL_MEM_FENCE);
  event_t copyFirstPtr = async_work_group_copy(myPtrs, ptrs + dstRow - threadid, ndo, 0);
  wait_group_events(1, &copyFirstPtr);

  // TODO I should probably openclize this forloop as well.
  for(int firstSrcRow = 0; firstSrcRow < srcRows; firstSrcRow += BLOCK_SIZE) {
    //if the ptrs were next to each other, we could use async_work_group_strided_copy
    // but they're not :(
    int nRowsToDo =  min(BLOCK_SIZE, srcRows - firstSrcRow);
    for(int i = 0; i < ndo; ++i) 
      copyInEvents[i] = async_work_group_copy(block[i], // block(i, ::)
        src + srcMajorStride * myPtrs[i] + firstSrcRow, // src(firstSrcRow --> nRowsToDo, myPtrs(i))
        nRowsToDo, 0); //

    wait_group_events(ndo, copyInEvents);
    
    // each block[i] now contains the slice src(firstSrcRow --> nRowsToDo, myPtrs[i]) 
    // so we want thread i to write block[i][j] to dst(dstRow, firstSrcRow + j)

    for(int j = 0; j < nRowsToDo && threadid < ndo; j += 1) {
      int dstCol = firstSrcRow + j;
      dst[dstCol * dstMajorStride + dstRow] = block[threadid][j];
    }

  }

}
"""
  }




}
