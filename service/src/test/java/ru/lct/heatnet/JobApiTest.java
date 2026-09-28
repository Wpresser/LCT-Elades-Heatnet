package ru.lct.heatnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.storage.dir=target/test-data")
@AutoConfigureMockMvc
class JobApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void swaggerDocsAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void uploadCreatesJobThatFinishes() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "tiny.geojson", "application/geo+json",
                "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes());
        MvcResult created = mvc.perform(multipart("/api/jobs").file(file)).andExpect(status().isCreated()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        JsonNode job = null;
        for (int i = 0; i < 100; i++) {
            job = json.readTree(mvc.perform(get("/api/jobs/" + id)).andReturn().getResponse().getContentAsString());
            String s = job.get("status").asText();
            if (s.equals("DONE") || s.equals("FAILED")) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(job).isNotNull();
        assertThat(job.get("status").asText()).isIn("DONE", "FAILED");
        assertThat(job.get("inputBytes").asLong()).isEqualTo(file.getSize());
    }

    @Test
    void unknownJobIs404() throws Exception {
        mvc.perform(get("/api/jobs/no-such-job")).andExpect(status().isNotFound());
    }
}
