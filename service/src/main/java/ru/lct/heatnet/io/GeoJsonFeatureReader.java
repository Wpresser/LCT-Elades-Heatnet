package ru.lct.heatnet.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Потоковое чтение GeoJSON FeatureCollection: в памяти одновременно только один Feature.
 * Порядок полей верхнего уровня произвольный.
 */
public final class GeoJsonFeatureReader {

    public interface Handler {
        default void onCrs(JsonNode crs) {
        }

        void onFeature(JsonNode feature, long index);
    }

    private GeoJsonFeatureReader() {
    }

    /** @return число прочитанных Feature */
    public static long read(Path file, ObjectMapper mapper, Handler handler) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JsonParser p = mapper.createParser(in)) {
            // NaN/Infinity в числах не роняют весь файл: такой объект отбрасывается проверками по объектам
            p.enable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS);
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new InputFormatException("Ожидался JSON-объект FeatureCollection");
            }
            String type = null;
            boolean sawFeatures = false;
            long count = 0;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                JsonToken value = p.nextToken();
                switch (field) {
                    case "type":
                        type = p.getValueAsString();
                        break;
                    case "crs":
                        handler.onCrs(p.readValueAsTree());
                        break;
                    case "features":
                        if (value != JsonToken.START_ARRAY) {
                            throw new InputFormatException("Поле features должно быть массивом");
                        }
                        JsonToken t;
                        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                            if (t != JsonToken.START_OBJECT) {
                                throw new InputFormatException("Элемент features №" + count + " не является объектом");
                            }
                            JsonNode feature = p.readValueAsTree();
                            handler.onFeature(feature, count++);
                        }
                        sawFeatures = true;
                        break;
                    default:
                        p.skipChildren();
                }
            }
            if (type != null && !"FeatureCollection".equals(type)) {
                throw new InputFormatException("Ожидался type=FeatureCollection, получено: " + type);
            }
            if (!sawFeatures) {
                throw new InputFormatException("В файле нет массива features");
            }
            return count;
        }
    }
}
