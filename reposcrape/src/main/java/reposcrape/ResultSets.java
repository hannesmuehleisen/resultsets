package reposcrape;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;

public class ResultSets {
  private String outputDir;
  private String inputDir;
  private List<String> apiKeys;
  private int threads;

  private static Logger log = Logger.getLogger(ResultSets.class);

  public ResultSets(String inputDir, String outputDir, List<String> apiKeys,
      int threads) {
    this.outputDir = outputDir;
    this.inputDir = inputDir;
    this.apiKeys = apiKeys;
    this.threads = threads;
  }

  private class RetrievalTask implements Runnable {
    private File inputfile;
    private File outputfile;

    private static final int REQUEST_LENGTH = 6500;

    public RetrievalTask(File infile, File outfile) {
      this.inputfile = infile;
      this.outputfile = outfile;
    }

    private void payload(HttpClient httpClient, String url, OutputStream out) {
      HttpGet request = new HttpGet(url);
      request.addHeader("content-type", "application/json");
      try {
        HttpResponse result = httpClient.execute(request);
        if (result.getStatusLine().getStatusCode() != 200) {
          throw new IOException(
              "HTTP error " + result.getStatusLine().getStatusCode() + " ("
                  + EntityUtils.toString(result.getEntity(), "UTF-8") + ")");
        }
        String json = EntityUtils.toString(result.getEntity(), "UTF-8");
        JsonElement jelement = new JsonParser().parse(json);
        JsonObject jobject = jelement.getAsJsonObject();
        if (jobject.get("incomplete_results").getAsBoolean()) {
          log.warn("incomplete results :(");
        }
        JsonArray items = jobject.get("items").getAsJsonArray();
        Collection<String> seenIds = new ArrayList<String>();
        for (JsonElement jo : items) {
          JsonObject repo = jo.getAsJsonObject().get("repository")
              .getAsJsonObject();
          String repoName = repo.get("full_name").getAsString();
          String repoId = repo.get("id").getAsString();
          if (!seenIds.contains(repoId)) {
            log.info(repoName);
            out.write((repoId + "\t" + repoName + "\n").getBytes());
          }
          seenIds.add(repoId);
        }

      } catch (Exception e) {
        log.error(e.getMessage());
        try {
          Thread.sleep(1000 * 60 * 5);
        } catch (InterruptedException e1) {
          // TODO Auto-generated catch block
          e1.printStackTrace();
        }
        // try again?!
        payload(httpClient, url, out);
      }
    }

    public void run() {
      log.info(inputfile);
      if (outputfile.exists() && outputfile.length() > 0) {
        log.info("Skipping chunk, output file exists " + outputfile);
        return;
      }
      CloseableHttpClient httpClient = HttpClientBuilder.create().build();
      FileOutputStream out = null;
      File outFile = null;

      try {
        outFile = File.createTempFile("reposcrape", "tsv");
        out = new FileOutputStream(outFile);
        // oh, java...
        BufferedReader isr = new BufferedReader(
            new InputStreamReader(new FileInputStream(inputfile)));
        String repos = "";
        String line = null;
        while ((line = isr.readLine()) != null) {
          if (line.trim().equals("")) {
            continue;
          }
          String[] linep = line.split("\t");
          // skip forks
          if (linep[2] == "true") {
            continue;
          }
          repos += "+repo:" + linep[1];

          if (repos.length() + 147 > REQUEST_LENGTH) {
            String url = "https://api.github.com/search/code?access_token="
                + apiKeys.get(new Random().nextInt(apiKeys.size()))
                + "&sort=indexed&q=ResultSet+language:Java+in:file+fork:false"
                + repos;
            payload(httpClient, url, out);
            repos = "";
          }
        }
        // don't forget, stuff in the back
        if (repos.length() > 0) {
          String url = "https://api.github.com/search/code?access_token="
              + apiKeys.get(new Random().nextInt(apiKeys.size()))
              + "&sort=indexed&q=ResultSet+language:Java+in:file+fork:false"
              + repos;
          payload(httpClient, url, out);
        }

        Files.move(outFile.toPath(), outputfile.toPath());

        isr.close();
        out.close();
      } catch (IOException e) {
        log.warn(e.getMessage());
      }

    }
  }

  public void retrieve() {
    BlockingQueue<Runnable> taskQueue = new LinkedBlockingDeque<Runnable>(1000);
    ExecutorService ex = new ThreadPoolExecutor(threads, threads,
        Integer.MAX_VALUE, TimeUnit.DAYS, taskQueue,
        new ThreadPoolExecutor.DiscardPolicy());

    File inputDirF = new File(inputDir);
    for (File infile : inputDirF.listFiles(new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.matches("repositories_\\d+");
      }
    })) {
      while (taskQueue.remainingCapacity() < 1) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          // ok
        }
      }

      File outfile = new File(outputDir + File.separator
          + infile.getName().replace("repositories", "resultsets"));

      log.info(outfile);
      ex.submit(new RetrievalTask(infile, outfile));
    }

    ex.shutdown();
    try {
      ex.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      // ok
    }
  }

  public static void main(String[] args) throws JSAPException {
    JSAP jsap = new JSAP();

    jsap.registerParameter(new FlaggedOption("output").setShortFlag('o')
        .setLongFlag("output").setStringParser(JSAP.STRING_PARSER)
        .setRequired(true).setHelp("Output directory"));

    jsap.registerParameter(new FlaggedOption("input").setShortFlag('i')
        .setLongFlag("input").setStringParser(JSAP.STRING_PARSER)
        .setRequired(true).setHelp("Input directory"));

    jsap.registerParameter(new FlaggedOption("apikey")
        .setAllowMultipleDeclarations(true).setShortFlag('a')
        .setLongFlag("apikey").setStringParser(JSAP.STRING_PARSER)
        .setRequired(true).setHelp("Github API key (need many)"));

    jsap.registerParameter(new FlaggedOption("threads").setShortFlag('t')
        .setLongFlag("threads").setStringParser(JSAP.INTEGER_PARSER)
        .setRequired(true).setHelp("Threads to use (probably 1)"));

    JSAPResult res = jsap.parse(args);

    if (!res.success()) {
      @SuppressWarnings("rawtypes")
      Iterator errs = res.getErrorMessageIterator();
      while (errs.hasNext()) {
        System.err.println(errs.next());
      }
      System.err.println(
          "Usage: " + jsap.getUsage() + "\nParameters: " + jsap.getHelp());
      System.exit(-1);
    }
    new ResultSets(res.getString("input"), res.getString("output"),
        Arrays.asList(res.getStringArray("apikey")), res.getInt("threads"))
            .retrieve();
  }

}
