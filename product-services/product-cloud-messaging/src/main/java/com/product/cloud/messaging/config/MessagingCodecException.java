package com.product.cloud.messaging.config;

/**
 * 事件信封编解码失败（毒消息/非法 occurredAt 等）。
 */
public class MessagingCodecException extends RuntimeException {

    public MessagingCodecException(String message) {
        super(message);
    }

    public MessagingCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
