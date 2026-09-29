package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.storage.dir=target/raw-upload-test", "spring.servlet.multipart.max-file-size=40B"})
@AutoConfigureMockMvc
class RawUploadLimitTest {
    @Autowired MockMvc mvc;

    @Test
    void rawUploadHonorsMultipartSizeLimit() throws Exception {
        mvc.perform(post("/api/jobs/raw?autostart=false").contentType("application/geo+json").content(new byte[41]))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rawUploadWithoutContentLengthCannotBypassLimit() throws Exception {
        mvc.perform(post("/api/jobs/raw?autostart=false").contentType("application/geo+json").content(new byte[41])
                .with(request -> {
                    MockHttpServletRequest chunked = new MockHttpServletRequest() {
                        @Override public long getContentLengthLong() { return -1; }
                    };
                    chunked.setMethod("POST");
                    chunked.setRequestURI("/api/jobs/raw");
                    chunked.setContentType("application/geo+json");
                    chunked.setParameter("autostart", "false");
                    chunked.setContent(new byte[41]);
                    return chunked;
                }))
                .andExpect(status().isPayloadTooLarge());
    }
}
