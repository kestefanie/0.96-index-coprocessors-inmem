package ca.mcgill.distsys.hbase.indexcoprocessorsinmem.pluggableIndex;

import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.hbase.util.Bytes;

import ca.mcgill.distsys.hbase96.indexcommonsinmem.ByteUtil;

public class HybridRowIndex implements Comparable<HybridRowIndex>{

	private byte [] rowKey;
	private TreeSet<byte[]> pkRefs;
	private transient ReentrantReadWriteLock rwLock;
	
	
	public HybridRowIndex(byte [] rowKey){
		pkRefs = new TreeSet<byte[]>(ByteUtil.BYTES_COMPARATOR);
        rwLock = new ReentrantReadWriteLock(true);
        this.rowKey = Bytes.copy(rowKey);
	} 
	
	public TreeSet<byte[]> getPKRefs(){
		return pkRefs;
	}
	
	public byte[] getRowKey(){
		return rowKey;
	}
	
	public void add(byte[] value){
		rwLock.writeLock().lock();
		pkRefs.add(value);
		rwLock.writeLock().unlock();
	}
	
	public void remove(byte[] value) {
		rwLock.writeLock().lock();
		pkRefs.remove(value);
		rwLock.writeLock().unlock();
	}

	@Override
	public int compareTo(HybridRowIndex arg0) {
		
		// left > right, return positive. left == right, return 0
		return Bytes.compareTo(this.rowKey, arg0.getRowKey());
	}
	
	public static void main(String[] args) {
		HybridRowIndex index1 = new HybridRowIndex(Bytes.toBytes("abc"));
		HybridRowIndex index2 = new HybridRowIndex(Bytes.toBytes("ad"));
		System.out.println(index2.compareTo(index1));

	}

}
