package io.qzz.rendimarzikri;

import android.os.Bundle;
import android.graphics.Color;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private EditText codeEditor;
    private TextView outputConsole;
    private TextView statusText;
    private TextView lineNumbers;
    private Button runButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile String pythonInitError = null;

    private static final Pattern KEYWORDS = Pattern.compile(
        "\\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|True|try|while|with|yield|None)\\b"
    );

    private static final Pattern STRINGS = Pattern.compile(
        "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
    );

    private static final Pattern COMMENTS = Pattern.compile("#[^\\n]*");
    private static final Pattern NUMBERS = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    public static class PythonIO {

        private final MainActivity activity;

        public PythonIO(MainActivity activity) {
            this.activity = activity;
        }

        public void onOutput(String text) {
            if (text == null || text.isEmpty()) return;

            activity.runOnUiThread(() -> {
                activity.outputConsole.append(text);
            });
        }

        public String readLine() {
            CountDownLatch latch = new CountDownLatch(1);
            String[] answer = new String[1];

            activity.runOnUiThread(() -> {
                EditText input = new EditText(activity);
                input.setSingleLine(true);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setHint("Enter Python input");

                AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Python Input")
                    .setMessage("Program meminta masukan:")
                    .setView(input)
                    .setPositiveButton("OK", (d, w) -> {
                        answer[0] = input.getText().toString();
                        activity.outputConsole.append(answer[0] + "\n");
                        latch.countDown();
                    })
                    .setNegativeButton("Cancel", (d, w) -> {
                        answer[0] = null;
                        latch.countDown();
                    })
                    .create();

                dialog.setOnCancelListener(d -> {
                    answer[0] = null;
                    latch.countDown();
                });

                dialog.show();
                input.requestFocus();
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            return answer[0];
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        codeEditor = findViewById(R.id.codeEditor);
        outputConsole = findViewById(R.id.outputConsole);
        statusText = findViewById(R.id.statusText);
        lineNumbers = findViewById(R.id.lineNumbers);
        runButton = findViewById(R.id.runButton);
        Button clearButton = findViewById(R.id.clearButton);

        codeEditor.setText(
            "nama = \"Rendi Marzikri\"\n" +
            "nilai = [90, 85, 95]\n\n" +
            "print(\"Halo,\", nama)\n" +
            "print(\"Rata-rata:\", sum(nilai) / len(nilai))"
        );

        codeEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateLineNumbers(s);
                highlightSyntax(s);
            }
        });

        updateLineNumbers(codeEditor.getText());
        highlightSyntax(codeEditor.getText());

        runButton.setOnClickListener(v -> runPython());

        clearButton.setOnClickListener(v -> {
            outputConsole.setText("");
            statusText.setText("Console cleared.");
        });

        initializePython();
    }

    private void initializePython() {
        statusText.setText("Initializing Python runtime...");

        executor.execute(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }

                runOnUiThread(() ->
                    statusText.setText("Python 3.13 ready.")
                );

            } catch (Throwable e) {
                pythonInitError = Log.getStackTraceString(e);

                runOnUiThread(() ->
                    statusText.setText("Python initialization failed.")
                );
            }
        });
    }

    private void runPython() {
        String code = codeEditor.getText().toString();

        if (code.trim().isEmpty()) {
            outputConsole.setText("No Python code to execute.");
            return;
        }

        runButton.setEnabled(false);
        statusText.setText("Running Python...");
        outputConsole.setText("");

        executor.execute(() -> {
            boolean hasError = false;

            try {
                if (pythonInitError != null) {
                    throw new IllegalStateException(pythonInitError);
                }

                if (!Python.isStarted()) {
                    throw new IllegalStateException("Python runtime is not ready.");
                }

                Python py = Python.getInstance();
                PyObject builtins = py.getBuiltins();
                PyObject globals = builtins.callAttr("dict");

                PythonIO io = new PythonIO(this);

                globals.asMap().put(
                    PyObject.fromJava("__user_code__"),
                    PyObject.fromJava(code)
                );

                globals.asMap().put(
                    PyObject.fromJava("__callback__"),
                    PyObject.fromJava(io)
                );

                String runner =
                    "import sys, traceback\n" +
                    "_old_stdout = sys.stdout\n" +
                    "_old_stderr = sys.stderr\n" +
                    "_old_stdin = sys.stdin\n" +
                    "class _Stream:\n" +
                    "    def write(self, text):\n" +
                    "        if text:\n" +
                    "            __callback__.onOutput(text)\n" +
                    "        return len(text)\n" +
                    "    def flush(self):\n" +
                    "        pass\n" +
                    "class _Input:\n" +
                    "    def readline(self, *args):\n" +
                    "        value = __callback__.readLine()\n" +
                    "        if value is None:\n" +
                    "            return ''\n" +
                    "        return value + '\\n'\n" +
                    "_stream = _Stream()\n" +
                    "sys.stdout = _stream\n" +
                    "sys.stderr = _stream\n" +
                    "sys.stdin = _Input()\n" +
                    "__status__ = 'ok'\n" +
                    "try:\n" +
                    "    exec(compile(__user_code__, '<editor>', 'exec'), {'__name__': '__main__'})\n" +
                    "except BaseException:\n" +
                    "    __status__ = 'error'\n" +
                    "    traceback.print_exc()\n" +
                    "finally:\n" +
                    "    sys.stdout = _old_stdout\n" +
                    "    sys.stderr = _old_stderr\n" +
                    "    sys.stdin = _old_stdin\n";

                builtins.callAttr("exec", runner, globals);

                PyObject result = globals.asMap().get(
                    PyObject.fromJava("__status__")
                );

                hasError = result != null && result.toString().equals("error");

            } catch (Throwable e) {
                hasError = true;
                String error = Log.getStackTraceString(e);

                runOnUiThread(() ->
                    outputConsole.append("\nRuntime Error:\n" + error)
                );
            }

            final boolean errorStatus = hasError;

            runOnUiThread(() -> {
                statusText.setText(
                    errorStatus ? "Finished with error." : "Execution finished."
                );
                runButton.setEnabled(true);
            });
        });
    }

    private void updateLineNumbers(Editable editable) {
        int count = 1;

        for (int i = 0; i < editable.length(); i++) {
            if (editable.charAt(i) == '\n') {
                count++;
            }
        }

        StringBuilder numbers = new StringBuilder();

        for (int i = 1; i <= count; i++) {
            numbers.append(i);

            if (i < count) {
                numbers.append('\n');
            }
        }

        lineNumbers.setText(numbers.toString());
    }

    private void highlightSyntax(Editable editable) {
        ForegroundColorSpan[] oldSpans = editable.getSpans(
            0,
            editable.length(),
            ForegroundColorSpan.class
        );

        for (ForegroundColorSpan span : oldSpans) {
            editable.removeSpan(span);
        }

        applyColor(editable, KEYWORDS, Color.rgb(198, 120, 221));
        applyColor(editable, NUMBERS, Color.rgb(209, 154, 102));
        applyColor(editable, STRINGS, Color.rgb(152, 195, 121));
        applyColor(editable, COMMENTS, Color.rgb(128, 140, 155));
    }

    private void applyColor(Editable editable, Pattern pattern, int color) {
        Matcher matcher = pattern.matcher(editable);

        while (matcher.find()) {
            editable.setSpan(
                new ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}

