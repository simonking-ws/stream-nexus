package com.simonking.stream.nexus.sse.core;

import tools.jackson.databind.ObjectMapper;
import com.simonking.stream.nexus.common.enums.SseEvent;
import com.simonking.stream.nexus.common.model.NexusMessage;
import com.simonking.stream.nexus.sse.connection.SseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 单条消息写入
 *
 * <p>消息体预先序列化为 JSON 字符串再交给 SSE，避免每个连接重复一次对象转换。
 *
 * @author simonking
 */
@Component
@RequiredArgsConstructor
public class SseSender {

    private final JsonMapper jsonMapper;

    /**
     * 向指定连接发送消息
     *
     * @throws IOException 连接已失效或写入阻塞失败
     */
    public void send(SseClient client, NexusMessage<?, SseEvent> message) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (message.getId() != null) {
            builder.id(message.getId());
        }
        builder.name(message.getEvent().name())
                .data(jsonMapper.writeValueAsString(message), MediaType.APPLICATION_JSON);
        client.getEmitter().send(builder);
    }
}
