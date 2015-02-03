package com.techlooper.crawlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.techlooper.utils.PropertyManager;
import com.techlooper.utils.Utils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by phuonghqh on 1/22/15.
 */
public class GitHubUserCrawler {

  private static Logger LOGGER = LoggerFactory.getLogger(GitHubUserCrawler.class);

  private static PoolingHttpClientConnectionManager clientConnectionManager = new PoolingHttpClientConnectionManager();

  private static enum DIVISION {NOT, BINARY}

  public static void main(String[] args) throws IOException, InterruptedException, ParseException {
    String outputDirectory = PropertyManager.properties.getProperty("githubUserCrawler.outputDirectory");
    Utils.sureDirectory(outputDirectory);
//    final String[] countries = {"vietnam"x, "japan"x, "thailand"x, "singapore"x, "malaysia"x, "indonesia"x, "australia"x, "china"x, "india"x, "korea", "taiwan",
//      "spain", "ukraine", "poland", "russia", "bulgaria", "turkey", "greece", "serbia", "romania", "belarus", "lithuania", "estonia",
//      "italy", "portugal", "colombia", "brazil", "chile", "argentina", "venezuela", "bolivia", "mexico"};


    final String[] countries = {"india"};

    ExecutorService executor = Executors.newFixedThreadPool(20);

    for (String country : countries) {
      doCountry(country, executor);
    }

    executor.shutdown();
    LOGGER.debug("DONE DONE DONE!!!!!");
  }

  private static DateTime divRange(DateTime from, DateTime to, DIVISION div) {
    if (div == DIVISION.NOT) {
      return null;
    }

    DateTime right = null;
    if (from.getYear() < to.getYear()) {
      LOGGER.debug("Divide YEAR");
      right = to.withYear((from.getYear() + to.getYear()) / 2)
        .monthOfYear().withMaximumValue()
        .dayOfMonth().withMaximumValue();
    }
    else if (from.getMonthOfYear() < to.getMonthOfYear()) {
      LOGGER.debug("Divide MONTH");
      right = to.withMonthOfYear((from.getMonthOfYear() + to.getMonthOfYear()) / 2).dayOfMonth().withMaximumValue();
    }
    else if (from.getDayOfMonth() < to.getDayOfMonth()) {
      LOGGER.debug("Divide DAY");
      right = to.withDayOfMonth((from.getDayOfMonth() + to.getDayOfMonth()) / 2);
    }
    return right;
  }

  private static void doCountry(String country, ExecutorService executor) throws IOException, InterruptedException, ParseException {
    int currentYear = Calendar.getInstance(Locale.US).get(Calendar.YEAR);
    boolean stop = false;
    String fromTo = String.format("2007-01-01..%d-12-31", currentYear);
    DIVISION div = DIVISION.NOT;
    DateTimeFormatter dateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd").withLocale(Locale.US);

    do {
      DateTime from = dateTimeFormatter.parseDateTime(fromTo.split("\\..")[0]);
      DateTime to = dateTimeFormatter.parseDateTime(fromTo.split("\\..")[1]);

      DateTime toDiv = divRange(from, to, div);
      if (toDiv == null) {
        LOGGER.debug("From {} - To {} cant be divided.", from, to);
      }
      else {
        to = toDiv;
      }

      String count = count(country, String.format("%d-%02d-%02d", from.getYear(), from.getMonthOfYear(), from.getDayOfMonth()),
        String.format("%d-%02d-%02d", to.getYear(), to.getMonthOfYear(), to.getDayOfMonth()));

      Integer totalUsers = Integer.parseInt(count.split(",")[0]);
      Integer maxPageNumber = Integer.parseInt(count.split(",")[1]);

      LOGGER.debug("Total users are {} , paging to {} ", totalUsers, maxPageNumber);
      if (from.isEqual(to)) {
        totalUsers = 1000;
        maxPageNumber = 100;
        LOGGER.debug("  => Not min enough but we should start crawling.");
      }

      if (totalUsers <= 1000) {
        LOGGER.debug("  => Start crawling");
        //////////////////////////
        // start crawling here //
        /////////////////////////
        for (int pageNumber = 1; pageNumber <= maxPageNumber; ++pageNumber) {
          executor.execute(new JobQuery(country, from.toString("yyyy-MM-dd"), to.toString("yyyy-MM-dd"), pageNumber));
        }

        String lastDate = currentYear + "-12-31";
        stop = lastDate.equals(to.toString("yyyy-MM-dd"));

        div = DIVISION.NOT;
        from = to.plusDays(1);
        to = dateTimeFormatter.parseDateTime(lastDate);
      }
      else {
        div = DIVISION.BINARY;
        LOGGER.debug("  => Not min enough");
      }

      fromTo = String.format("%s..%s", from.toString("yyyy-MM-dd"), to.toString("yyyy-MM-dd"));
    }
    while (!stop);
  }

  private static String count(String country, String createdFrom, String createdTo) throws IOException, InterruptedException {
    String userId = PropertyManager.properties.getProperty("githubUserCrawler.import.io.userId");
    String apiKey = PropertyManager.properties.getProperty("githubUserCrawler.import.io.apiKey");
    String urlTemplate = PropertyManager.properties.getProperty("githubUserCrawler.user.searchTemplate");
    String connectorId = PropertyManager.properties.getProperty("githubUserCrawler.import.io.connector.github.totalUsers");

    boolean tryAgain = true;
    Integer count = 0;
    Integer maxPageNumber = 1;

    while (tryAgain) {
      String queryUrl = String.format(urlTemplate, 1, country, createdFrom, createdTo);
      LOGGER.debug("Catch total using url: " + queryUrl);
      String content = Utils.postIIOAndReadContent(connectorId, userId, apiKey, queryUrl);
      if (content == null) {
        continue;
      }

      JsonNode root = Utils.readIIOResult(content);
      if (!root.isArray()) {
        LOGGER.debug("Error result => query: {}", queryUrl);
        continue;
      }
      tryAgain = false;

      if (root.size() == 0) {
        LOGGER.debug("Empty result => query: {}", queryUrl);
        continue;
      }
      root = root.get(0);

      count = root.get("total_users").asInt();
      if (count <= 0) {
        LOGGER.debug("No total => query: " + queryUrl);
        continue;
      }
      maxPageNumber = count / 10 + (count % 10 > 0 ? 1 : 0);
    }

    LOGGER.debug("Total user for the period of {}..{}: {} , max-page: ", createdFrom, createdTo, count, maxPageNumber);
    return count + "," + maxPageNumber;
  }

  private static class JobQuery implements Runnable {

    private final String country;
    private final String createdFrom;
    private final String createdTo;
    private Integer pageNumber;

    public JobQuery(String country, String createdFrom, String createdTo, Integer pageNumber) {
      this.country = country;
      this.createdFrom = createdFrom;
      this.createdTo = createdTo;
      this.pageNumber = pageNumber;
    }

    public void run() {
      LOGGER.debug("Starting new thread...");

      String userId = PropertyManager.properties.getProperty("githubUserCrawler.import.io.userId");
      String apiKey = PropertyManager.properties.getProperty("githubUserCrawler.import.io.apiKey");
      String urlTemplate = PropertyManager.properties.getProperty("githubUserCrawler.user.searchTemplate");
      String connectorId = PropertyManager.properties.getProperty("githubUserCrawler.import.io.connector.github");
      String outputDirectory = PropertyManager.properties.getProperty("githubUserCrawler.outputDirectory");

      String period = String.format("%s..%s", createdFrom, createdTo);
      boolean tryAgain = true;
      String filename = String.format("%s%s.%s.%d.json", outputDirectory, country, period, pageNumber);
      File f = new File(filename);
      if (f.exists() && !f.isDirectory()) {
        return;
      }

      try {
        while (tryAgain) {
          String queryUrl = String.format(urlTemplate, pageNumber, country, createdFrom, createdTo);
          LOGGER.debug("Query users using url: " + queryUrl);
          String content = Utils.postIIOAndReadContent(connectorId, userId, apiKey, queryUrl);
          JsonNode root = Utils.readIIOResult(content);
          if (!root.isArray()) {
            LOGGER.debug("Error result => query: {}", queryUrl);
            continue;
          }
          tryAgain = false;

          if (root.size() == 0) {
            LOGGER.debug("Empty result => Not write to file {}", filename);
            continue;
          }

          Utils.writeToFile(root, filename);
          LOGGER.debug("OK => wrote to file: " + filename);
        }
      }
      catch (Exception e) {
        LOGGER.error("Can not do crawler", e);
      }
    }
  }
}
