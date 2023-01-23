package org.matsim.run.analysis;

import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// TODO
// https://www.regionalstatistik.de/genesis/online?operation=abruftabelleBearbeiten&levelindex=1&levelid=1674130185923&auswahloperation=abruftabelleAuspraegungAuswaehlen&auswahlverzeichnis=ordnungsstruktur&auswahlziel=werteabruf&code=19321-Z-21&auswahltext=&werteabruf=starten#abreadcrumb

@CommandLine.Command(name = "commuter", description = "Generate matrix with number of commuters from home to work")
public class CommuterAnalysis implements MATSimAppCommand, PersonAlgorithm {

	@CommandLine.Option(names = "--population", description = "Input population", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output csv", required = true)
	private Path output;

	@CommandLine.Option(names = "--attr", description = "Shape file attribute to be used for aggregating", required = true)
	private String shpAttribute;

	@CommandLine.Mixin
	private ShpOptions shp;

	@CommandLine.Mixin
	private CrsOptions crs;

	@CommandLine.Mixin
	private CsvOptions csv;

	private ShpOptions.Index index;

	private Map<OD, AtomicInteger> counts;

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null)
			throw new IllegalArgumentException("Shape file is required!");

		index = shp.createIndex(crs.getInputCRS(), shpAttribute);

		Population population = PopulationUtils.readPopulation(input.toString());
		counts = new ConcurrentHashMap<>();

		ParallelPersonAlgorithmUtils.run(population, Runtime.getRuntime().availableProcessors(), this);

		try (CSVPrinter printer = csv.createPrinter(output)) {
			printer.printRecord("from", "to", "n");
			for (Map.Entry<OD, AtomicInteger> e : counts.entrySet()) {
				printer.print(e.getKey().origin);
				printer.print(e.getKey().destination);
				printer.print(e.getValue().intValue());
				printer.println();
			}
		}

		return 0;
	}

	@Override
	public void run(Person person) {

		Plan plan = person.getSelectedPlan();

		List<Activity> activities = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

		Optional<Activity> home = activities.stream()
				.filter(act -> act.getType().startsWith("home"))
				.findFirst();

		Optional<Activity> work = activities.stream()
				.filter(act -> act.getType().startsWith("work"))
				.findFirst();

		// Home and work activities are required
		if (home.isEmpty() || work.isEmpty())
			return;

		Coord homeCoord = home.get().getCoord();
		Coord workCoord = work.get().getCoord();

		String from = index.query(homeCoord);
		String to = index.query(workCoord);

		// Count commuters
		counts.computeIfAbsent(new OD(from, to), k -> new AtomicInteger(0))
				.incrementAndGet();
	}

	private record OD(String origin, String destination) { }

}
