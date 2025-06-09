package com.walpro.autobuilder.client.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatResponse {
    private List<Choice> choices;

    @Data
    public static class Choice {
        private Message message;
    }
    
    public String getFirstContent() {
        if (choices != null && !choices.isEmpty()) {
            return choices.get(0).getMessage().getContent();
        }
        return null;
    }
}
