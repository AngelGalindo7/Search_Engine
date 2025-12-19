import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.io.IOException;

public class FilterDEV {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Enter a single directory path");
            return;
        }

        Path dir = Paths.get(args[0]);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(path -> {
                        String jsonString = Files.readString(path);
                        Map<String, String> jsonMap = gson.fromJson(jsonString, Map.class);

                        System.out.println(jsonMap.get("url"));
                        System.out.println();
                        // try {
                        //     Files.delete(path);
                        // } catch (IOException e) {
                        //     System.out.println(e.getMessage());
                        // }
                        //
                  });
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}
