package com.example.aichat.service.output;

import com.example.aichat.llm.LlmResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TextOutputPolicy implements OutputPolicy {

    @Override
    public String type() {
        return "text";
    }

    @Override
    public OutputPolicyResult apply(LlmResult result) {
        if (!StringUtils.hasText(result.content())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LLM provider returned an invalid response"
            );
        }
        return new OutputPolicyResult(result.content().trim(), null);
    }
}
