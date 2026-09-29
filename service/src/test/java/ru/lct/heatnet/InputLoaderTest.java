package ru.lct.heatnet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputFormatException;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.model.InputObjects.Target;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

public class InputLoaderTest {

    /** Конкурсный набор: в репозитории команды — task/sources, в пакете аудита — project/00_official_current. */
    public static final Path DATASET = firstExisting(
            Paths.get("../task/sources/Датасет скорректированный.geojson"),
            Paths.get("../../00_official_current/Датасет скорректированный.geojson"));

    static Path firstExisting(Path... candidates) {
        for (Path p : candidates) {
            if (java.nio.file.Files.exists(p)) {
                return p;
            }
        }
        return candidates[0];
    }

    private final InputLoader loader = new InputLoader(new ObjectMapper(), new AppProperties().getLimits());

    @Test
    void loadsCompetitionDataset() throws Exception {
        InputModel m = loader.load(DATASET);

        assertThat(m.sources).hasSize(1);
        assertThat(m.pipes).hasSize(29);
        assertThat(m.chambers).hasSize(9);
        assertThat(m.targets).hasSize(17);
        // ограничения вне окон поиска не загружаются, но учитываются в диагностике
        assertThat(distinctIds(m) + m.diagnostics.get("restriction.outside_windows")).isEqualTo(88);
        assertThat(distinctIds(loader.load(DATASET, 10.0))).isEqualTo(88);
        assertThat(m.diagnostics.get("restriction_type.oks")).isEqualTo(85);
        assertThat(m.diagnostics.get("restriction_type.water")).isEqualTo(2);
        assertThat(m.diagnostics.get("restriction_type.railway")).isEqualTo(1);
        assertThat(m.diagnostics.get("input.skipped")).isZero();
        assertThat(m.targets.stream().mapToDouble(t -> t.flowTph).sum()).isCloseTo(488.72, within(1e-9));
        assertThat(m.targets).allMatch(t -> t.id.isNumber());

        // все 17 целей внутри своих полигонов ОКС (DATA_AUDIT), 12 и 15 — в одном полигоне 92
        assertThat(m.diagnostics.get("targets.inside_own_oks")).isEqualTo(17);
        assertThat(ownPolygonIds(m, 12)).containsExactly("92");
        assertThat(ownPolygonIds(m, 15)).containsExactly("92");

        // сеть связна, источник на сети, у каждой камеры 1..4 примыкания
        assertThat(m.diagnostics.get("network.components")).isEqualTo(1);
        for (ExistingChamber c : m.chambers) {
            assertThat(c.existingAdjacency).as("камера %s", c.id).isBetween(1, 4);
        }
        assertThat(m.diagnostics.warnings()).noneMatch(w -> w.contains("не лежит"));
    }

    private static long distinctIds(InputModel m) {
        return m.restrictions.stream().map(r -> r.id).distinct().count();
    }

    private static List<String> ownPolygonIds(InputModel m, long targetId) {
        Target t = m.targets.stream().filter(x -> x.id.equals(FeatureId.of(targetId))).findFirst().orElseThrow(AssertionError::new);
        return m.ownOksOf(t).stream().map(r -> r.id.toString()).distinct().collect(Collectors.toList());
    }

    @Test
    void keepsStringIdsAndSkipsBrokenObjects(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("in.geojson");
        Files.write(f, ("{\"features\":["
                + feature("{\"id\":\"t-1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":12.5}", point(37.60, 55.70))
                + "," + feature("{\"id\":\"t-2\",\"object_type\":\"oks_connection_point\"}", point(37.60, 55.70))
                + "," + feature("{\"id\":7,\"object_type\":\"heat_network\",\"diameter\":400}",
                "{\"type\":\"MultiLineString\",\"coordinates\":[[[37.601,55.701],[37.602,55.701]],[[37.602,55.701],[37.603,55.702]]]}")
                + "," + feature("{\"id\":\"x\",\"object_type\":\"metro\"}", point(37.6, 55.7))
                + "," + feature("{\"id\":\"far\",\"object_type\":\"restriction\",\"restriction_type\":\"oks\"}",
                "{\"type\":\"Polygon\",\"coordinates\":[[[38.5,56.5],[38.501,56.5],[38.501,56.501],[38.5,56.5]]]}")
                + "," + feature("{\"id\":\"near\",\"object_type\":\"restriction\",\"restriction_type\":\"road\"}",
                "{\"type\":\"Polygon\",\"coordinates\":[[[37.6005,55.7],[37.6006,55.7],[37.6006,55.7005],[37.6005,55.7]]]}")
                + "," + feature("{\"id\":\"odd\",\"object_type\":\"restriction\",\"restriction_type\":\"metro_line\"}", point(37.6, 55.7))
                + "],\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:OGC:1.3:CRS84\"}},"
                + "\"type\":\"FeatureCollection\"}").getBytes(StandardCharsets.UTF_8));

        InputModel m = loader.load(f);
        assertThat(m.targets).hasSize(1);
        assertThat(m.targets.get(0).id.isNumber()).isFalse();
        assertThat(m.targets.get(0).id.toString()).isEqualTo("t-1");
        assertThat(m.pipes).hasSize(2);
        // точка без flow_tph не теряется: маршрут не строится, в выгрузке она среди неподключённых с причиной
        assertThat(m.invalidTargets.keySet()).extracting(t -> t.id.toString()).containsExactly("t-2");
        assertThat(m.diagnostics.get("input.invalid_flow")).isEqualTo(1);
        assertThat(m.diagnostics.get("input.skipped")).isEqualTo(0);
        assertThat(m.diagnostics.get("input.unknown_object_type")).isEqualTo(1);
        assertThat(m.restrictions).extracting((Restriction r) -> r.id.toString()).containsExactly("near");
        assertThat(m.diagnostics.get("restriction.outside_windows")).isEqualTo(1);
        assertThat(m.diagnostics.get("restriction.unsupported")).isEqualTo(1);
    }

    @Test
    void targetOnTheFacadeIsSnappedInsideItsOwnBuilding(@TempDir Path dir) throws Exception {
        // ТЗ §1.1: точка подключения на границе ОКС. Для расчёта — 5 см внутрь, в выгрузке — исходная точка
        Path f = dir.resolve("facade.geojson");
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[37.6000,55.7000],[37.6010,55.7000],[37.6010,55.7006],"
                + "[37.6000,55.7006],[37.6000,55.7000]]]}";
        Files.write(f, ("{\"type\":\"FeatureCollection\",\"features\":["
                + feature("{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":10}", point(37.6005, 55.7000))
                + "," + feature("{\"id\":2,\"object_type\":\"restriction\",\"restriction_type\":\"oks\"}", square)
                + "]}").getBytes(StandardCharsets.UTF_8));
        InputModel m = loader.load(f);
        Target t = m.targets.get(0);
        assertThat(t.point.distance(t.origin)).isBetween(0.04, 0.06);
        assertThat(m.ownOksOf(t)).isNotEmpty();
        assertThat(m.diagnostics.get("targets.on_facade")).isEqualTo(1);
    }

    @Test
    void rejectsForeignCrs(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("in.geojson");
        Files.write(f, "{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:3857\"}},\"features\":[]}"
                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> loader.load(f)).isInstanceOf(InputFormatException.class).hasMessageContaining("EPSG:3857");
    }

    static String feature(String props, String geometry) {
        return "{\"type\":\"Feature\",\"properties\":" + props + ",\"geometry\":" + geometry + "}";
    }

    @Test
    void duplicateNodeIdsAreRejectedButStringAndNumberRemainDistinct(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("duplicate.geojson");
        String a = feature("{\"id\":71,\"object_type\":\"oks_connection_point\",\"flow_tph\":1}", point(37.6, 55.7));
        Files.writeString(f, "{\"type\":\"FeatureCollection\",\"features\":[" + a + "," + a + "]}");
        assertThatThrownBy(() -> loader.load(f)).isInstanceOf(InputFormatException.class).hasMessageContaining("id");
        String b = feature("{\"id\":\"71\",\"object_type\":\"oks_connection_point\",\"flow_tph\":1}", point(37.6, 55.7));
        Files.writeString(f, "{\"type\":\"FeatureCollection\",\"features\":[" + a + "," + b + "]}");
        InputModel m = loader.load(f);
        assertThat(m.targets).hasSize(2);
        assertThat(m.diagnostics.get("input.duplicate_node_id")).isZero();
    }

    @Test
    void nonfiniteFlowOrGeometryRemainUnconnectedWithFinitePenalty(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nonfinite.geojson");
        String a = feature("{\"id\":\"bad-flow\",\"object_type\":\"oks_connection_point\",\"flow_tph\":NaN}", point(37.6, 55.7));
        String b = feature("{\"id\":\"bad-geometry\",\"object_type\":\"oks_connection_point\",\"flow_tph\":2}", "{\"type\":\"Point\",\"coordinates\":[Infinity,55.7]}");
        Files.writeString(f, "{\"type\":\"FeatureCollection\",\"features\":[" + a + "," + b + "]}");
        InputModel m = loader.load(f);
        assertThat(m.invalidTargets).hasSize(2);
        assertThat(m.invalidTargets.keySet()).allMatch(t -> Double.isFinite(t.flowTph));
    }

    static String point(double lon, double lat) {
        return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
    }
}
