package org.matsim.run.analysis;

import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.util.HashMap;
import java.util.Map;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.run.analysis.LausitzDrtAnalysis.*;

@CommandLine.Command(
	name = "od-data-simwrapper",
	description = "Create OD data for a shp file to display it in a Simwrapper OD plot."
)
@CommandSpec(requireRunDirectory = true,
	produces = {"shp_based_od_relations.csv"}
)
public class GenerateOdDataForSimwrapperOdViz implements MATSimAppCommand {
	@CommandLine.Mixin
	private final InputOptions input = InputOptions.ofCommand(GenerateOdDataForSimwrapperOdViz.class);
	@CommandLine.Mixin
	private final OutputOptions output = OutputOptions.ofCommand(GenerateOdDataForSimwrapperOdViz.class);
	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	private static final String DEP_TIME_S = "dep_time_s";

	public static void main(String[] args) {
		new GenerateOdDataForSimwrapperOdViz().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		String tripsPath = globFile(input.getRunDirectory(), "*output_trips.csv.gz").toString();

		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING));

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Table freightTrips = trips.where(trips.stringColumn(TRIP_ID).containsString("commercialPersonTraffic")
			.or(trips.stringColumn(TRIP_ID).containsString("freight"))
			.or(trips.stringColumn(TRIP_ID).containsString("goodsTraffic")));

		Table tripsWithoutFreight = trips.where(trips.stringColumn(TRIP_ID).isNotIn(freightTrips.stringColumn(TRIP_ID)));

		DoubleColumn depTimeS = DoubleColumn.create(DEP_TIME_S, new Double[tripsWithoutFreight.rowCount()]);
		tripsWithoutFreight.addColumns(depTimeS);

		PtLineAnalysis ptLineAnalysis = new PtLineAnalysis(null, null, null);

		for (int i = 0; i < tripsWithoutFreight.rowCount(); i++) {
			Row row = tripsWithoutFreight.row(i);

			double depTime = ptLineAnalysis.parseTimeManually(row.getString("dep_time"));
			row.setDouble(DEP_TIME_S, depTime);
		}

		new LausitzDrtAnalysis().aggregateAndWriteDrtODRelations(tripsWithoutFreight, shp, output, "shp_based_od_relations.csv",
			"start_x", "start_y", "end_x", "end_y", DEP_TIME_S);
		return 0;
	}
}
