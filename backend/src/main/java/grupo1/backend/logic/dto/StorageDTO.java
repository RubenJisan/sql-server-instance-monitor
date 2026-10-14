package grupo1.backend.logic.dto;

import java.util.List;

public record StorageDTO(
        Instance instance,
        String database,
        List<String> databases,
        Summary summary,
        List<Filegroup> filegroups,
        List<DataFile> files,
        List<StorageObject> objects,
        Growth growth,
        List<String> warnings
) {

    public record Instance(String name, String serverName) {
    }

    public record Summary(
            String size,
            String sizeTrend,
            String allocated,
            String free,
            Integer freePercent,
            String freeStatus,
            String monthlyGrowth,
            String runway,
            String growthStatus
    ) {
    }

    public record Filegroup(String name, String used, String total, Integer usedPercent) {
    }

    public record DataFile(
            String name,
            String path,
            String kind,
            String fileGroup,
            String size,
            boolean autoGrowth,
            String growth
    ) {
    }

    public record StorageObject(String database, String name, String parent, String type, String size) {
    }

    public record Growth(String source, List<GrowthPoint> points) {
    }

    public record GrowthPoint(String month, double sizeMb, String size) {
    }
}
