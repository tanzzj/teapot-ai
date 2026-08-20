package com.teamer.teapot.ai.core.storage;

import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * base64 内联策略（SPEC §20.3，默认）：bytes → data URL，与 §19 现链路产物一致。
 */
@Component
public class InlineBase64StorageStrategy implements ImageStorageStrategy {

    @Override
    public String name() {
        return "base64";
    }

    @Override
    public StoredImage store(byte[] data, String mediaType) {
        String url = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(data);
        return new StoredImage(name(), url);
    }
}
