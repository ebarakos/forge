package forge.ai.nn;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.UUID;

import com.google.gson.stream.JsonWriter;

/**
 * Records every NN decision plus the final game outcome as JSONL
 * (newline-delimited JSON). Thread-safe: each game thread gets its own
 * writer instance. One file per game.
 *
 * <p>File creation is lazy — no file is created until the first decision
 * is recorded. This avoids thousands of empty files from snapshot restore
 * creating temporary controllers.
 */
public class TrainingDataWriter {
    private final String outputDir;
    private BufferedWriter fileWriter;
    private boolean closed;

    public TrainingDataWriter(String outputDir) {
        this.outputDir = outputDir;
    }

    private void ensureOpen() throws IOException {
        if (fileWriter == null && !closed) {
            File dir = new File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File outputFile = new File(outputDir,
                    "game_" + UUID.randomUUID() + "_" + System.currentTimeMillis() + ".jsonl");
            this.fileWriter = new BufferedWriter(new FileWriter(outputFile));
        }
    }

    public synchronized void recordDecision(int turn, String phase, DecisionType type,
                                            float[] state, float[][] options,
                                            int numOptions, int chosenIndex) {
        if (closed) return;
        try {
            ensureOpen();

            StringWriter sw = new StringWriter(512);
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("type").value("decision");
            jw.name("turn").value(turn);
            jw.name("phase").value(phase);
            jw.name("decisionType").value(type.name());

            jw.name("state");
            jw.beginArray();
            for (float v : state) {
                jw.value(v);
            }
            jw.endArray();

            jw.name("options");
            jw.beginArray();
            for (int i = 0; i < numOptions; i++) {
                jw.beginArray();
                for (float v : options[i]) {
                    jw.value(v);
                }
                jw.endArray();
            }
            jw.endArray();

            jw.name("numOptions").value(numOptions);
            jw.name("chosenIndex").value(chosenIndex);
            jw.endObject();
            jw.close();

            fileWriter.write(sw.toString());
            fileWriter.newLine();
            fileWriter.flush();
        } catch (IOException e) {
            System.err.println("TrainingDataWriter: failed to record decision — " + e.getMessage());
        }
    }

    public synchronized void recordOutcome(float result, int turns, String reason) {
        if (closed) return;
        try {
            ensureOpen();

            StringWriter sw = new StringWriter(128);
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("type").value("outcome");
            jw.name("result").value(result);
            jw.name("turns").value(turns);
            jw.name("reason").value(reason);
            jw.endObject();
            jw.close();

            fileWriter.write(sw.toString());
            fileWriter.newLine();
            fileWriter.flush();
        } catch (IOException e) {
            System.err.println("TrainingDataWriter: failed to record outcome — " + e.getMessage());
        }
    }

    public synchronized void close() {
        if (closed || fileWriter == null) return;
        closed = true;
        try {
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            System.err.println("TrainingDataWriter: failed to close — " + e.getMessage());
        }
    }
}
