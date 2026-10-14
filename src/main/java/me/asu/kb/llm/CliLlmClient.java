package me.asu.kb.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import me.asu.log.Log;

public class CliLlmClient implements LlmClient {

    private final String[] commandTemplate;

    public CliLlmClient(String... commandTemplate) {
        this.commandTemplate = commandTemplate;
    }

    public static boolean isAvailable(String command) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String[] checkCmd = os.contains("win") ? new String[]{"where", command} : new String[]{"which", command};
        try {
            Process process = new ProcessBuilder(checkCmd).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String generate(String prompt) {
        List<String> fullArgs = new ArrayList<>();
        for (String arg : commandTemplate) {
            fullArgs.add(arg.replace("{prompt}", prompt));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(fullArgs);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String result = reader.lines().collect(Collectors.joining("\n"));
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    Log.error("CLI LLM " + Arrays.toString(commandTemplate) + " failed with exit code " + exitCode + ": " + result);
                    // some tools might output to stderr, but we redirected it
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute CLI LLM: " + Arrays.toString(commandTemplate), e);
        }
    }

    @Override
    public String toString() {
        return "CliLlmClient" + Arrays.toString(commandTemplate);
    }
}
