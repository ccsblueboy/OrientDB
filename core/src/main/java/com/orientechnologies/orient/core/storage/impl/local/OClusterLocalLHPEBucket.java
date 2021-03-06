package com.orientechnologies.orient.core.storage.impl.local;

import com.orientechnologies.common.serialization.OBinaryConverter;
import com.orientechnologies.common.serialization.OBinaryConverterFactory;
import com.orientechnologies.orient.core.id.OClusterPositionNodeId;
import com.orientechnologies.orient.core.id.ONodeId;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.core.version.OVersionFactory;

/**
 * @author Andrey Lomakin
 * @since 26.07.12
 */
public final class OClusterLocalLHPEBucket {
  private static final OBinaryConverter CONVERTER            = OBinaryConverterFactory.getConverter();
  private static final boolean          nativeAcceleration   = CONVERTER.nativeAccelerationUsed();

  public static final int               BUCKET_CAPACITY      = 64;

  private static final int              VALUE_SIZE           = 13 + OVersionFactory.instance().getVersionSize();

  private static final int              KEY_SIZE             = 192;

  private static final int              BUCKET_SIZE_SIZE     = 1;

  private static final int              OVERFLOW_BUCKET_SIZE = 8;

  public static final int               BUCKET_SIZE_IN_BYTES = BUCKET_CAPACITY * (KEY_SIZE + VALUE_SIZE) + BUCKET_SIZE_SIZE
                                                                 + OVERFLOW_BUCKET_SIZE;

  private static final int              OVERFLOW_POS         = BUCKET_CAPACITY * (KEY_SIZE + VALUE_SIZE) + BUCKET_SIZE_SIZE;

  private static final int              FIRST_VALUE_POS      = BUCKET_CAPACITY * KEY_SIZE + BUCKET_SIZE_SIZE;

  private byte[]                        buffer;

  private long                          overflowBucketIndex  = -2;

  private final ONodeId[]               keys                 = new ONodeId[BUCKET_CAPACITY];
  private final OPhysicalPosition[]     positions            = new OPhysicalPosition[BUCKET_CAPACITY];

  private final boolean[]               positionsToUpdate    = new boolean[BUCKET_CAPACITY];
  private final boolean[]               keysToUpdate         = new boolean[BUCKET_CAPACITY];
  private boolean                       overflowWasChanged;

  private final OClusterLocalLHPEPS     clusterLocal;
  private final long                    position;

  private final boolean                 isOverflowBucket;

  public OClusterLocalLHPEBucket(byte[] buffer, final OClusterLocalLHPEPS clusterLocal, final long position,
      final boolean overflowBucket) {
    this.buffer = buffer;

    this.clusterLocal = clusterLocal;
    this.position = position;
    this.isOverflowBucket = overflowBucket;
  }

  public OClusterLocalLHPEBucket(final OClusterLocalLHPEPS clusterLocal, final long position, final boolean overflowBucket) {
    this.buffer = new byte[BUCKET_SIZE_IN_BYTES];

    this.clusterLocal = clusterLocal;
    this.position = position;
    this.isOverflowBucket = overflowBucket;
  }

  public long getFilePosition() {
    return position;
  }

  public int getSize() {
    return buffer[0];
  }

  public boolean isOverflowBucket() {
    return isOverflowBucket;
  }

  public long getOverflowBucket() {
    if (nativeAcceleration)
      return CONVERTER.getLong(buffer, OVERFLOW_POS) - 1;

    if (overflowBucketIndex != -2)
      return overflowBucketIndex;

    overflowBucketIndex = CONVERTER.getLong(buffer, OVERFLOW_POS) - 1;

    return overflowBucketIndex;
  }

  public void addPhysicalPosition(OPhysicalPosition physicalPosition) {
    int index = buffer[0];

    setKey(((OClusterPositionNodeId) physicalPosition.clusterPosition).getNodeId(), index);

    positions[index] = physicalPosition;
    buffer[0]++;

    positionsToUpdate[index] = true;

    addToStoreList();
  }

  public void removePhysicalPosition(int index) {
    // buffer[0]--;
    //
    // if (buffer[0] > 0) {
    // setKey(getKey(buffer[0]), index);
    // positions[index] = getPhysicalPosition(buffer[0]);
    //
    // keysToUpdate[index] = true;
    // positionsToUpdate[index] = true;
    // }
    //
    // addToStoreList();
  }

  public void setOverflowBucket(long overflowBucket) {
    if (nativeAcceleration) {
      CONVERTER.putLong(buffer, OVERFLOW_POS, overflowBucket + 1);
      addToStoreList();

      return;
    }

    this.overflowBucketIndex = overflowBucket;

    overflowWasChanged = true;

    addToStoreList();
  }

  public long getKey(int index) {
    // if (nativeAcceleration)
    // return CONVERTER.getLong(buffer, index * KEY_SIZE + BUCKET_SIZE_SIZE);
    //
    // long result = keys[index];
    //
    // if (result > -1)
    // return result;
    //
    // result = CONVERTER.getLong(buffer, index * KEY_SIZE + BUCKET_SIZE_SIZE);
    //
    // keys[index] = result;
    //
    return -1;
  }

  public OPhysicalPosition getPhysicalPosition(int index) {
    OPhysicalPosition physicalPosition = positions[index];

    if (physicalPosition != null)
      return physicalPosition;

    physicalPosition = new OPhysicalPosition();

    int position = BUCKET_SIZE_SIZE + BUCKET_CAPACITY * KEY_SIZE + VALUE_SIZE * index;

    // physicalPosition.clusterPosition = getKey(index);

    physicalPosition.dataSegmentId = CONVERTER.getInt(buffer, position);
    position += 4;

    physicalPosition.dataSegmentPos = CONVERTER.getLong(buffer, position);
    position += 8;

    physicalPosition.recordType = buffer[position];
    position += 1;

    physicalPosition.recordVersion.getSerializer().fastReadFrom(buffer, position, physicalPosition.recordVersion);

    return physicalPosition;
  }

  public static int getDataSegmentIdOffset(int index) {
    return FIRST_VALUE_POS + index * VALUE_SIZE;
  }

  public static byte[] serializeDataSegmentId(int dataSegmentId) {
    final byte[] serializedDataSegmentId = new byte[4];
    CONVERTER.putInt(serializedDataSegmentId, 0, dataSegmentId);

    return serializedDataSegmentId;
  }

  public static byte[] serializeDataPosition(long dataPosition) {
    final byte[] serializedDataPosition = new byte[8];
    CONVERTER.putLong(serializedDataPosition, 0, dataPosition);

    return serializedDataPosition;
  }

  public static int getRecordTypeOffset(int index) {
    return FIRST_VALUE_POS + index * VALUE_SIZE + 12;
  }

  public static int getVersionOffset(int index) {
    return FIRST_VALUE_POS + index * VALUE_SIZE + 13;
  }

  public static byte[] serializeVersion(ORecordVersion version) {
    return version.getSerializer().toByteArray(version);
  }

  public byte[] getBuffer() {
    return buffer;
  }

  public void serialize() {
    int position = 1;

    int size = buffer[0];
    if (!nativeAcceleration)
      for (int i = 0; i < size; i++) {
        if (keysToUpdate[i]) {
          // CONVERTER.putLong(buffer, position, keys[i]);
          keysToUpdate[i] = false;
        }

        position += KEY_SIZE;
      }

    position = FIRST_VALUE_POS;
    for (int i = 0; i < size; i++) {
      if (positionsToUpdate[i]) {
        OPhysicalPosition physicalPosition = positions[i];

        CONVERTER.putInt(buffer, position, physicalPosition.dataSegmentId);
        position += 4;

        CONVERTER.putLong(buffer, position, physicalPosition.dataSegmentPos);
        position += 8;

        buffer[position] = physicalPosition.recordType;
        position += 1;

        position += physicalPosition.recordVersion.getSerializer().fastWriteTo(
								buffer, position, physicalPosition.recordVersion);

        positionsToUpdate[i] = false;
      } else
        position += VALUE_SIZE;
    }

    if (overflowWasChanged)
      CONVERTER.putLong(buffer, OVERFLOW_POS, overflowBucketIndex + 1);
  }

  private void setKey(final ONodeId key, int index) {
    // if (nativeAcceleration) {
    // CONVERTER.putLong(buffer, index * KEY_SIZE + BUCKET_SIZE_SIZE, key);
    // } else {
    // keys[index] = key;
    // keysToUpdate[index] = true;
    // }
  }

  private void addToStoreList() {
    if (isOverflowBucket)
      clusterLocal.addToOverflowStoreList(this);
    else
      clusterLocal.addToMainStoreList(this);
  }
}
