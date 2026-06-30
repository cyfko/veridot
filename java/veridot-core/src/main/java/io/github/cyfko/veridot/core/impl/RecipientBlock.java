package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a RecipientBlock within a SECURE_PAYLOAD entry (§12.3).
 */
public record RecipientBlock(String recipientSid, long recipientKeyEpochId, byte[] encryptedKey) {

    public byte[] encode() {
        byte[] sidBytes = recipientSid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] block = new byte[2 + sidBytes.length + 8 + 2 + encryptedKey.length];
        
        // recipientSidLen (u16)
        block[0] = (byte) ((sidBytes.length >> 8) & 0xFF);
        block[1] = (byte) (sidBytes.length & 0xFF);
        // recipientSid
        System.arraycopy(sidBytes, 0, block, 2, sidBytes.length);
        
        int offset = 2 + sidBytes.length;
        // recipientKeyEpochId (u64)
        long tempEpochId = recipientKeyEpochId;
        for (int i = 7; i >= 0; i--) {
            block[offset + i] = (byte) (tempEpochId & 0xFF);
            tempEpochId >>= 8;
        }
        offset += 8;
        
        // encryptedKeyLen (u16)
        block[offset] = (byte) ((encryptedKey.length >> 8) & 0xFF);
        block[offset + 1] = (byte) (encryptedKey.length & 0xFF);
        // encryptedKey
        System.arraycopy(encryptedKey, 0, block, offset + 2, encryptedKey.length);
        
        return block;
    }
    
    public static List<RecipientBlock> decodeList(byte[] bytes) {
        List<RecipientBlock> list = new ArrayList<>();
        if (bytes == null || bytes.length == 0) return list;
        int offset = 0;
        while (offset < bytes.length) {
            if (offset + 2 > bytes.length) break;
            int sidLen = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            offset += 2;
            
            if (offset + sidLen > bytes.length) break;
            String sid = new String(bytes, offset, sidLen, java.nio.charset.StandardCharsets.UTF_8);
            offset += sidLen;
            
            if (offset + 8 > bytes.length) break;
            long keyEpochId = 0;
            for (int i = 0; i < 8; i++) {
                keyEpochId = (keyEpochId << 8) | (bytes[offset + i] & 0xFF);
            }
            offset += 8;
            
            if (offset + 2 > bytes.length) break;
            int keyLen = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            offset += 2;
            
            if (offset + keyLen > bytes.length) break;
            byte[] encKey = new byte[keyLen];
            System.arraycopy(bytes, offset, encKey, 0, keyLen);
            offset += keyLen;
            
            list.add(new RecipientBlock(sid, keyEpochId, encKey));
        }
        return list;
    }
}
