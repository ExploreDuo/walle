package com.meituan.android.walle;

import com.meituan.android.walle.internal.ApkUtil;
import com.meituan.android.walle.internal.Pair;
import com.meituan.android.walle.internal.SignatureNotFoundException;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.meituan.android.walle.PayloadReader.CHANNEL_KEY;


public class PayloadWriter {
    /**
     * write channel with channel fixed id
     * @param apkFile apk file
     * @param channel channel
     * @return if success
     */
    public static boolean putChannel(File apkFile, String channel) {
        return putChannel(apkFile, channel, null);
    }

    /**
     * write channel & extra info with channel fixed id
     * @param apkFile apk file
     * @param channel channel （nullable)
     * @param extraInfo extra info (don't use {@link PayloadReader#CHANNEL_KEY PayloadReader.CHANNEL_KEY} as your key)
     * @return if success
    */
    public static boolean putChannel(File apkFile, String channel, Map<String,String> extraInfo) {
        Map<String, String> newData = new HashMap<>();
        Map<String, String> existsData = PayloadReader.getChannelInfoMap(apkFile);
        if (existsData != null) {
            newData.putAll(existsData);
        }
        if (extraInfo != null) {
            extraInfo.remove(CHANNEL_KEY);// can't use
            newData.putAll(extraInfo);
        }
        if (channel != null && channel.length() > 0) {
            newData.put(CHANNEL_KEY, channel);
        }
        JSONObject jsonObject = new JSONObject(newData);
        return putRawChannelInfo(apkFile, jsonObject.toString());
    }
    /**
     * write custom content with channel fixed id
     * @param apkFile apk file
     * @param string custom content
     * @return if success
     */
    public static boolean putRawChannelInfo(File apkFile, String string) {
        try {
            byte[] bytes = string.getBytes(ApkUtil.DEFAULT_CHARSET);
            ByteBuffer byteBuffer = ByteBuffer.allocate(bytes.length);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            byteBuffer.put(bytes, 0, bytes.length);
            byteBuffer.flip();
            return put(apkFile, ApkUtil.APK_CHANNEL_BLOCK_ID, byteBuffer);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return false;
    }
    /**
     * remove channel id content
     * @param apkFile apk file
     * @return if success
     */
    public static boolean removeChannel(File apkFile) {
        return handleApkSigningBlock(apkFile, new ApkSigningBlockHandler() {
            @Override
            public ApkSigningBlock handle(Map<Integer, ByteBuffer> originIdValues) {
                ApkSigningBlock apkSigningBlock = new ApkSigningBlock();
                Set<Map.Entry<Integer, ByteBuffer>> entrySet = originIdValues.entrySet();
                for (Map.Entry<Integer, ByteBuffer> entry : entrySet) {
                    if (entry.getKey() != ApkUtil.APK_CHANNEL_BLOCK_ID) {
                        ApkSigningPayload payload = new ApkSigningPayload(entry.getKey(), entry.getValue());
                        apkSigningBlock.addPayload(payload);
                    }
                }
                return apkSigningBlock;
            }
        });
    }
    /**
     * put (id, buffer) into apk, update if id exists
     * @param apkFile  apk file
     * @param id id
     * @param buffer buffer
     * @return if success
     */
    public static boolean put(File apkFile, int id, ByteBuffer buffer) {
        Map<Integer, ByteBuffer> idValues = new HashMap<>();
        idValues.put(id, buffer);
        return putAll(apkFile, idValues);
    }
    /**
     * put new idValues into apk, update if id exists
     *
     * @param apkFile apk file
     * @param idValues id value. NOTE: use unknown IDs. DO NOT use ID that have already been used.  See <a href='https://source.android.com/security/apksigning/v2.html'>APK Signature Scheme v2</a>
     * @return if success
     */
    public static boolean putAll(File apkFile, final Map<Integer, ByteBuffer> idValues) {
        return handleApkSigningBlock(apkFile, new ApkSigningBlockHandler() {
            @Override
            public ApkSigningBlock handle(Map<Integer, ByteBuffer> originIdValues) {
                if (idValues != null && !idValues.isEmpty()) {
                    originIdValues.putAll(idValues);
                }
                ApkSigningBlock apkSigningBlock = new ApkSigningBlock();
                Set<Map.Entry<Integer, ByteBuffer>> entrySet = originIdValues.entrySet();
                for (Map.Entry<Integer, ByteBuffer> entry : entrySet) {
                    ApkSigningPayload payload = new ApkSigningPayload(entry.getKey(), entry.getValue());
                    apkSigningBlock.addPayload(payload);
                }
                return apkSigningBlock;
            }
        });
    }

    private interface ApkSigningBlockHandler {
        ApkSigningBlock handle(Map<Integer, ByteBuffer> originIdValues);
    }

    private static boolean handleApkSigningBlock(File apkFile, ApkSigningBlockHandler handler) {
        RandomAccessFile fIn = null;
        FileChannel fileChannel = null;
        try {
            fIn = new RandomAccessFile(apkFile, "rw");
            fileChannel = fIn.getChannel();
            boolean hasComment = ApkUtil.checkComment(fileChannel);
            if (hasComment) {
                throw new IllegalArgumentException("zip data already has an archive comment");
            }

            long centralDirStartOffset = ApkUtil.findCentralDirStartOffset(fileChannel);
            // Find the APK Signing Block. The block immediately precedes the Central Directory.
            Pair<ByteBuffer, Long> apkSigningBlockAndOffset =  ApkUtil.findApkSigningBlock(fileChannel, centralDirStartOffset);
            ByteBuffer apkSigningBlock2 = apkSigningBlockAndOffset.getFirst();
            long apkSigningBlockOffset = apkSigningBlockAndOffset.getSecond();

            Map<Integer, ByteBuffer> originIdValues = ApkUtil.findIdValues(apkSigningBlock2);
            // Find the APK Signature Scheme v2 Block inside the APK Signing Block.
            ByteBuffer apkSignatureSchemeV2Block = originIdValues.get(ApkUtil.APK_SIGNATURE_SCHEME_V2_BLOCK_ID);

            if (apkSignatureSchemeV2Block == null) {
                throw new SignatureNotFoundException(
                        "No APK Signature Scheme v2 block in APK Signing Block");
            }


            ApkSigningBlock apkSigningBlock = handler.handle(originIdValues);

            if (apkSigningBlockOffset != 0 && centralDirStartOffset != 0) {

                // read CentralDir
                fIn.seek(centralDirStartOffset);
                byte[] centralDirBytes = new byte[(int) (fileChannel.size() - centralDirStartOffset)];
                fIn.read(centralDirBytes);

                fileChannel.position(apkSigningBlockOffset);

                long length = apkSigningBlock.writeApkSigningBlock(fIn);

                // store CentralDir
                fIn.write(centralDirBytes);
                // update length
                fIn.setLength(fIn.getFilePointer());

                // update CentralDir Offset

                // End of central directory record (EOCD)
                // Offset	Bytes	Description[23]
                // 0	        4	    End of central directory signature = 0x06054b50
                // 4	        2	    Number of this disk
                // 6	        2	    Disk where central directory starts
                // 8	        2	    Number of central directory records on this disk
                // 10	        2	    Total number of central directory records
                // 12	        4	    Size of central directory (bytes)
                // 16	        4	    Offset of start of central directory, relative to start of archive
                // 20	        2	    Comment length (n)
                // 22	        n	    Comment

                fIn.seek(fileChannel.size() - 6);
                // 6 = 2(Comment length) + 4 (Offset of start of central directory, relative to start of archive)
                ByteBuffer temp = ByteBuffer.allocate(4);
                temp.order(ByteOrder.LITTLE_ENDIAN);
                temp.putInt((int) (centralDirStartOffset + length + 8 - (centralDirStartOffset - apkSigningBlockOffset)));
                // 8 = size of block in bytes (excluding this field) (uint64)
                temp.flip();
                fIn.write(temp.array());

                return true;
            }
        } catch (IOException ignore) {
            ignore.printStackTrace();
        } catch (SignatureNotFoundException ignore) {
        } finally {
            try {
                if (fileChannel != null) {
                    fileChannel.close();
                }
                if (fIn != null) {
                    fIn.close();
                }
            } catch (IOException e) {
            }
        }
        return false;
    }
}
