package org.matsim.run.analysis;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVPrinter;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "download-gemeinden", description = "Download all existing Gemeinen from Regionalstatistik")
public class DownloadGemeinden implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(DownloadGemeinden.class);

	@CommandLine.Option(names = "--username", required = true, description = "Username for regionalstatistik.de")
	private String username;

	@CommandLine.Option(names = "--password", defaultValue = "${GENESIS_PW}", interactive = true, description = "Password for regionalstatistik.de")
	private String password;

	@CommandLine.Option(names = "--output", description = "Output csv", required = true)
	private Path output;

	@CommandLine.Mixin
	private CsvOptions csv;

	public static void main(String[] args) {
		new DownloadGemeinden().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		RequestConfig config = RequestConfig.custom()
				.setConnectionRequestTimeout(5, TimeUnit.MINUTES)
				.setResponseTimeout(5, TimeUnit.MINUTES)
				.build();

		ObjectMapper mapper = new ObjectMapper();

		if (password.isBlank()) {
			log.error("No password given, either set GENESIS_PW or use --password to enter it.");
			return 1;
		}


		try (CloseableHttpClient client = HttpClients.createDefault()) {

			HttpGet httpGet = new HttpGet(String.format("https://www.regionalstatistik.de/genesisws/rest/2020/catalogue/values2variable?username=%s&password=%s&name=PGEMEIN&area=all&pagelength=7000",
					username, password));

			httpGet.setConfig(config);

			log.info("Querying service...");

			JsonNode tree = client.execute(httpGet, response -> mapper.readTree(response.getEntity().getContent()));

			try (CSVPrinter printer = csv.createPrinter(output)) {

				printer.printRecord("code", "name");

				JsonNode list = tree.get("List");

				log.info("Processing {} entries", list.size());

				for (int i = 0; i < list.size(); i++) {
					JsonNode entry = list.get(i);

					printer.print(entry.get("Code").asText());
					printer.print(entry.get("Content").asText());
					printer.println();
				}
			}
		}

		return 0;
	}

}
