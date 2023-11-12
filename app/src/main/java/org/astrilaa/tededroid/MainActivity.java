package org.astrilaa.tededroid;

import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;

import com.chimbori.crux.articles.ArticleExtractor;


import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

// support for language detection in the future
//import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
//import org.apache.tika.language.detect.LanguageDetector;
//import org.apache.tika.language.detect.LanguageResult;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;

public class MainActivity extends AppCompatActivity {
    private final String instructionsText = "1) Add a new article with the New button\n" +
                                      "2) Choose an article\n" +
                                      "3) Read the chosen article with the Fwd and Back buttons\n" +
                                      "4) Delete the article by pressing the Delete button";
    private boolean spinnerTouched = false; // https://stackoverflow.com/a/35242382/653379
    private Spinner spinner;
    private TextView textView;
    private ImageView imageView;
    private ScaleGestureDetector scaleGestureDetector;
    private float mScaleFactor = 1.0f;

    // VARIABLES RELATED TO READING PROCESS - BEGIN
    private final String articlesPath = "articles";
    private List<String> lines = new ArrayList<>();
    private int lineNumber = 0;
    private int articlePart = 0;
    private List<String> articleParts = new ArrayList<>();
    private String currentArticle = null;
    // VARIABLES RELATED TO READING PROCESS - END

    private Map<String, URL> imagesDict = new HashMap<String, URL>();

    private class ArticleJC {
        // tuple (CruxArticle, Readability4JArticle)
        private Article readability4JArticle;
        private com.chimbori.crux.articles.Article cruxArticle;

        public ArticleJC(Article readability4JArticle, com.chimbori.crux.articles.Article cruxArticle) {
            this.readability4JArticle = readability4JArticle;
            this.cruxArticle = cruxArticle;
        }

        public Article getReadability4JArticle() {
            return this.readability4JArticle;
        }

        public com.chimbori.crux.articles.Article getCruxArticle() {
            return this.cruxArticle;
        }

    }

    class CallableGetArticleJC implements Callable<ArticleJC> {

        private String url;

        public CallableGetArticleJC(String url) {
            this.url = url;
        }

        @Override
        public ArticleJC call() throws Exception {
            HttpUrl httpUrl = HttpUrl.Companion.parse(url);
            Document doc = null;
            try {
                doc = Jsoup.connect(url).get();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (doc == null) {
                return new ArticleJC(null, null);
            }
            replaceImages(doc, url);

            Readability4J readability4J = new Readability4J(url, doc);
            Article article4J = readability4J.parse();

            com.chimbori.crux.articles.Article articleCrux = new ArticleExtractor(httpUrl, doc.toString())
                    .extractMetadata()
                    .extractContent()
                    .getArticle();

            return new ArticleJC(article4J, articleCrux);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textView = findViewById(R.id.text_label);
        textView.setTypeface(null, Typeface.BOLD);
        textView.setMovementMethod(new ScrollingMovementMethod());
        textView.setText(instructionsText);

        imageView = findViewById(R.id.imageView);
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        spinner = findViewById(R.id.spinner);
        updateSpinner();
        // https://stackoverflow.com/questions/13397933/android-spinner-avoid-onitemselected-calls-during-initialization
        spinner.setSelected(false);
        spinner.setSelection(0, false);
        //spinner.setSelection(Adapter.NO_SELECTION, true);
        spinner.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                spinnerTouched = true;
                return false;
            }
        });

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnerTouched) { // https://stackoverflow.com/a/35242382/653379
                    if (parent.getItemAtPosition(position).equals("Choose an article")) {
                        currentArticle = null;
                        articleParts.clear();
                        articlePart = 0;
                        lines.clear();
                        lineNumber = 0;
                        textView.setText(instructionsText);
                        imageView.setImageResource(R.drawable.smiling_hamster_small);
                        textView.scrollTo(0, 0);
                    } else {
                        String item = parent.getItemAtPosition(position).toString();
                        currentArticle = item.replace(" ", "_");
                        articleParts.clear();
                        articleParts.addAll(getAllTxtFiles(articlesPath + "/" + currentArticle));
                        Collections.sort(articleParts);
                        articlePart = 0;
                        lines.clear();
                        lineNumber = 0;

                        // restore bookmark if possible
                        boolean breaker = false;
                        int i = 0;
                        for (Iterator<String> apIterator = articleParts.iterator(); apIterator.hasNext(); i++) {
                            List<String> lns = getText(articlesPath + "/" + currentArticle + "/" + apIterator.next());
                            int j = 0;
                            for (Iterator<String> linesIterator = lns.iterator(); linesIterator.hasNext(); j++) {
                                if (linesIterator.next().equals("__BOOKMARK__")) {
                                    lineNumber = j;
                                    articlePart = i;
                                    breaker = true;
                                    break;
                                }
                            }
                            if (breaker) {
                                break;
                            }
                        } // end restore bookmark

                        if (!articleParts.isEmpty()) {
                            lines = getText(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
                            textView.setText(lines.get(lineNumber));

                            Bitmap imageBitmap = getRelatedImage(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
                            if (imageBitmap == null) {
                                if (articlePart > 0) {
                                    imageBitmap = getRelatedImage(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart - 1));
                                }
                            }
                            imageView.setImageBitmap(imageBitmap);
                        } else {
                            textView.setText("");
                            imageView.setImageBitmap(null);
                            lines.clear();
                            lineNumber = 0;
                        }
                        textView.scrollTo(0, 0);
                    }
                }
                spinnerTouched = false;
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (savedInstanceState != null) {
            textView.setText(savedInstanceState.getString("stateOfTextView"));
            Bitmap bitmap = savedInstanceState.getParcelable("stateOfImageView");
            imageView.setImageBitmap(bitmap);

            lines = savedInstanceState.getStringArrayList("lines");
            articleParts = savedInstanceState.getStringArrayList("articleParts");
            currentArticle = savedInstanceState.getString("currentArticle");
            lineNumber = savedInstanceState.getInt("lineNumber");
            articlePart = savedInstanceState.getInt("articlePart");
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {

        super.onSaveInstanceState(savedInstanceState);

        savedInstanceState.putString("stateOfTextView", textView.getText().toString());

        BitmapDrawable drawable = (BitmapDrawable) imageView.getDrawable();
        Bitmap bitmap = drawable.getBitmap();
        savedInstanceState.putParcelable("stateOfImageView", bitmap);

        savedInstanceState.putStringArrayList("lines", (ArrayList<String>) lines);
        savedInstanceState.putStringArrayList("articleParts", (ArrayList<String>) articleParts);
        savedInstanceState.putString("currentArticle", currentArticle);
        savedInstanceState.putInt("lineNumber", lineNumber);
        savedInstanceState.putInt("articlePart", articlePart);
    }
    private List<String> getAllTxtFiles(String directoryPath) {
        List<String> txtFiles = new ArrayList<>();

        File directory = new File(getExternalFilesDir(null), directoryPath);
        if (directory.exists() && directory.isDirectory()) {
            File [] files = directory.listFiles();
            if (files != null) {
                for (File file: files) {
                    if (file.isFile() && file.getName().endsWith(".txt")) {
                        txtFiles.add(file.getName());
                    }
                }
            }
        }

        return txtFiles;

    }
    private List<String> getText(String path) {
        File file = new File(getExternalFilesDir(null), path);
        List<String> lines = new ArrayList<>();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return lines;
    }
    private Bitmap getRelatedImage(String articlePartPath) {

        String imageFileName = articlePartPath.replace(".txt", ".jpg");
        File imageFile = new File(getExternalFilesDir(null), imageFileName);

        if (!imageFile.exists()) {
            imageFileName = articlePartPath.replace(".txt", "");
            imageFile = new File(getExternalFilesDir(null), imageFileName);
            if (!imageFile.exists()) {
                return null;
            }
        }

        return BitmapFactory.decodeFile(getExternalFilesDir(null) +  "/" + imageFileName);

    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        return true;
    }
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mScaleFactor *= scaleGestureDetector.getScaleFactor();
            mScaleFactor = Math.max(0.1f, Math.min(mScaleFactor, 10.0f));
            imageView.setScaleX(mScaleFactor);
            imageView.setScaleY(mScaleFactor);
            return true;
        }
    }
    public void updateSpinner() {
        List<String> articles = new ArrayList<>();
        articles.add(0, "Choose an article");

        File directory = new File(getExternalFilesDir(null), articlesPath);
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file: files) {
                    if (file.isDirectory()) {
                        articles.add(file.getName().replace("_", " "));
                    }
                }
            }

        }

        Collections.sort(articles.subList(1, articles.size()));
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(this, R.layout.spinner_item, articles);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
    }
    public void addNewArticle(View v) {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle("Enter URL");
        alert.setIcon(R.drawable.smiling_hamster_small);

        final EditText input = new EditText(this);
        alert.setView(input);

        alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String url = input.getText().toString();
                url = url.replace(".m.", "."); // works bad for mobile version of a site, that's why replacement
                try {
                    ExecutorService executorService = Executors.newFixedThreadPool(1);
                    Future<ArticleJC> futureArticle = executorService.submit(new CallableGetArticleJC(url));

                    ArticleJC articleJC = futureArticle.get();

                    executorService.shutdown();
                    while (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    }

                    Article readability4JArticle = articleJC.getReadability4JArticle();
                    com.chimbori.crux.articles.Article cruxArticle = articleJC.getCruxArticle();

                    if (readability4JArticle == null && cruxArticle == null) {
                        Toast.makeText(getApplicationContext(), "Failed to add article", Toast.LENGTH_LONG).show();
                        return;
                    }

                    String text4J = readability4JArticle.getTextContent();

                    String title = readability4JArticle.getTitle();

                    String textC = cruxArticle.getDocument().text();

                    if (title == null) {
                        title = text4J;
                        if (title == null) {
                            title = textC;
                        }
                    }
                    title = title.substring(0, Math.min(title.length(), 50));
                    title = title.replaceAll("[\\\\|?\u0000*<\":>+\\[\\]/']", "")
                            .replace(" ", "_"); // https://stackoverflow.com/questions/14480944/replacing-special-character-from-a-string-in-android

                    if (title.equals("") || (text4J == null || text4J.equals("")) && (textC.equals(""))) {
                        Toast.makeText(getApplicationContext(), "Failed to add article", Toast.LENGTH_LONG).show();
                        return;
                    }

                    List<String> sentences;
                    if (text4J.length() > textC.length()) {
                        sentences = stringToSentences(text4J);
                    } else {
                        sentences = stringToSentences(textC);
                    }

                    if (sentences.isEmpty()) {
                        Toast.makeText(getApplicationContext(), "Failed to add article", Toast.LENGTH_LONG).show();
                        return;
                    }


                    saveArticle(title, sentences);
                    updateSpinner();
                    Toast.makeText(getApplicationContext(), "Article added successfully", Toast.LENGTH_LONG).show();

                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(getApplicationContext(), "Failed to add article", Toast.LENGTH_LONG).show();
                }
            }
        });

        alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        alert.show();
    }
    public void forward(View v) {
        if (currentArticle == null) {
            return;
        }
        if (lineNumber < lines.size() - 1) {
            lineNumber++;
            textView.setText(lines.get(lineNumber));
            textView.scrollTo(0, 0);
        } else if (articlePart < articleParts.size() - 1) {
            articlePart++;
            lines = getText(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
            lineNumber = 0;
            textView.setText(lines.get(lineNumber));
            textView.scrollTo(0, 0);

            Bitmap imageBitmap = getRelatedImage(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
            if (imageBitmap == null) {
                if (articlePart > 0) {
                    imageBitmap = getRelatedImage(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart - 1));
                }
            }
            imageView.setImageBitmap(imageBitmap);
        }
    }
    public void backward(View v) {
        if (currentArticle == null) {
            return;
        }
        if (lineNumber > 0) {
            lineNumber--;
            textView.setText(lines.get(lineNumber));
            textView.scrollTo(0, 0);
        } else if (articlePart > 0) {
            articlePart--;
            lines = getText(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
            lineNumber = lines.size() - 1;
            textView.setText(lines.get(lineNumber));
            textView.scrollTo(0, 0);

            Bitmap imageBitmap = getRelatedImage(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
            if (imageBitmap == null) {
                if (articlePart > 0) {
                    imageBitmap = getRelatedImage(articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart - 1));
                }
            }
            imageView.setImageBitmap(imageBitmap);
        }
    }
    public void addBookmark(View v) {
        if (currentArticle == null ||
            spinner.getSelectedItem().toString().equals("Choose an article") ||
            articleParts.isEmpty()) {
            return;
        }
        if (lines.get(lineNumber).equals("__BOOKMARK__")) {
            return;
        }

        // removing an old bookmark
        for (String ap : articleParts) {
            List<String> lns = getText(articlesPath + "/" + currentArticle + "/" + ap);
            String oldText = String.join("\n", lns);
            if (oldText.contains("__BOOKMARK__\n")) {
                String newText = oldText.replace("__BOOKMARK__\n", "");
                File filePath = new File(getExternalFilesDir(null), articlesPath + "/" + currentArticle + "/" + ap);
                try {
                    FileWriter writer = new FileWriter(filePath);
                    writer.write(newText);
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        if (lines.contains("__BOOKMARK__")) {
            int ind = lines.indexOf("__BOOKMARK__");
            lines.remove("__BOOKMARK__");
            if (ind < lineNumber) {
                lineNumber--;
            }
        }
        // making a new bookmark
        lines.add(lineNumber, "__BOOKMARK__");
        String newText = String.join("\n", lines);
        File filePath = new File(getExternalFilesDir(null), articlesPath + "/" + currentArticle + "/" + articleParts.get(articlePart));
        try {
            FileWriter writer = new FileWriter(filePath);
            writer.write(newText);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        lineNumber++;
        Toast.makeText(getApplicationContext(), "Bookmark added successfully", Toast.LENGTH_LONG).show();

    }
    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child: fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }
    public void deleteArticle(View v) {
        if (currentArticle == null ||
            spinner.getSelectedItem().toString().equals("Choose an article")) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete article")
                .setMessage(String.format("Do you really want to delete article \"%s\"?", currentArticle.replace("_", " ")))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        String dir = articlesPath + "/" + currentArticle;
                        File directory = new File(getExternalFilesDir(null), dir);

                        if (directory.exists()) {
                            deleteRecursive(directory);
                        }
                        Toast.makeText(MainActivity.this, String.format("Article \"%s\" was deleted", currentArticle.replace("_", " ")), Toast.LENGTH_LONG).show();
                        updateSpinner();
                        currentArticle = null;
                        articleParts.clear();
                        articlePart = 0;
                        lines.clear();
                        lineNumber = 0;
                        textView.setText(instructionsText);
                        textView.scrollTo(0, 0);
                        imageView.setImageResource(R.drawable.smiling_hamster_small);
                    }})
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void downloadImage(URL imgUrl, File f) {

        ExecutorService service = Executors.newSingleThreadExecutor();
        service.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection connection = (HttpURLConnection) imgUrl.openConnection();
                    connection.setDoInput(true);
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    Bitmap bitmap = BitmapFactory.decodeStream(input);

                    if (bitmap != null) {
                        OutputStream outputStream = new FileOutputStream(f);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                        outputStream.flush();
                        outputStream.close();
                    }


                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        service.shutdown();
        try {
            while (!service.awaitTermination(10, TimeUnit.SECONDS)) {
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private void replaceImages(Document doc, String url) {
        // replace wikipedia infoboxes with its images
        if (url.contains("wikiped")) {
            Elements infoboxes = doc.getElementsByClass("infobox");
            for (Element infobox : infoboxes) {
                Elements images = infobox.getElementsByTag("img");
                Element divElement = new Element("div");
                for (Element img: images) {
                    divElement.appendChild(img);
                    divElement.appendText(" ");
                }
                infobox.replaceWith(divElement);
            }
        }

        // replace images in a doc
        imagesDict.clear();
        Elements images = doc.getElementsByTag("img");
        int i = 0;
        for (Element el: images) {
            TextNode text = new TextNode(String.format("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX%03d.", i));
            el.replaceWith(text);
            String imageLink = el.absUrl("src");
            try {
                if (imageLink == null || imageLink.equals("")) {
                    imageLink = el.attr("src");
                    URL ur  = new URL(url);
                    URI uri = ur.toURI();
                    imageLink = uri.resolve(imageLink).toString();
                }
                URL imageURL = new URL(imageLink);
                imagesDict.put(String.format("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX%03d", i), imageURL);
            } catch (Exception e) {
                e.printStackTrace();
                imagesDict.put(String.format("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX%03d", i), null);
                i++;
                continue;
            }
            i++;
        }
        // keep figures
        for (Element figure: doc.select("figure")) {
            figure.attr("crux-keep", "true");
        }
        // keep headings
        for (Element heading: doc.select("h1, h2, h3, h4, h5, h6, h7")) {
            heading.attr("crux-keep", "true");
            heading.appendText("headerx.");
        }
    }
    private List<String> stringToSentences(String text) {
        //String modelPath = getLanguageModelPath(languageCode);
        String modelPath = "en-sent.bin";
        List<String> sentences = new ArrayList<>();

        try (InputStream modelInput = getAssets().open(modelPath)) {
            SentenceModel model = new SentenceModel(modelInput);
            SentenceDetectorME sentenceDetector = new SentenceDetectorME(model);

            for (String s: sentenceDetector.sentDetect(text)) {
                sentences.add(s.replace("headerx.", ""));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return sentences;

    }
    private void saveArticle(String title, List<String> sentences) {
        String dir = articlesPath + "/" + title;
        File directory = new File(getExternalFilesDir(null), dir);

        if (directory.exists()) {
            return;
        } else {
            directory.mkdirs();
        }

        String fileName = "";

        //int i = 0; // number of file
        Pattern pattern = Pattern.compile("(XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\\d{3})");
        String textToWrite = "";
        for (String sentence : sentences) {
            textToWrite += sentence + "\n";
            Matcher matcher = pattern.matcher(sentence);
            if (matcher.find()) {
                String imgKey = matcher.group(1);
                String imgFileName = imgKey.substring(imgKey.length() - 3) + ".jpg";
                File file = new File(getExternalFilesDir(dir), imgFileName);
                if (!file.exists()) {
                    downloadImage(imagesDict.get(imgKey), file);

                    if (file.exists()) {
                        //fileName = String.format("%03d.txt", i);
                        //i++;
                        // write to text file information related to image just downloaded
                        fileName = imgKey.substring(imgKey.length() - 3) + ".txt";

                        File filePath = new File(getExternalFilesDir(dir), fileName);
                        //Log.i("we're going to write to file", fileName);

                        try {
                            FileWriter writer = new FileWriter(filePath);
                            writer.write(textToWrite.replace("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "image"));
                            writer.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        textToWrite = "";
                    }
                }
            }
        }
        if (!textToWrite.equals("")) {

            File [] files = directory.listFiles();
            if (files.length == 0) {
                fileName = "000.txt"; // There are no images, we will create only one text file
            } else {
                // last text without an image
                fileName = String.format("%03d.txt", Integer.parseInt(fileName.substring(0, 3)) + 1);
            }

            File filePath = new File(getExternalFilesDir(dir), fileName);
            //Log.i("we're going to write to file", fileName);

            try {
                FileWriter writer = new FileWriter(filePath);
                writer.write(textToWrite.replace("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "image"));
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

//    private static String getLanguageModelPath(String languageCode) {
//        // Maybe there will be support for other languages in the future
//        switch (languageCode) {
//            case "en":
//                return "en-sent.bin";
//            case "de":
//                return "de-sent.bin";
//            case "es":
//                return "es-sent.bin";
//            case "fr":
//                return "fr-sent.bin";
//            case "it":
//                return "it-sent.bin";
//            case "nl":
//                return "nl-sent.bin";
//            case "pl":
//                return "pl-sent.bin";
//            case "pt":
//                return "pt-sent.bin";
//            case "ru":
//                return "ru-sent.bin";
//            case "sv":
//                return "sv-sent.bin";
//            case "cs":
//                return "en-sent.bin"; // no czech language
//            case "ja":
//                return "ja-sent.bin";
//            case "ko":
//                return "ko-sent.bin";
//            case "zh":
//                return "zh-sent.bin";
//            default:
//                return "en-sent.bin";
//
//        }
//    }
}