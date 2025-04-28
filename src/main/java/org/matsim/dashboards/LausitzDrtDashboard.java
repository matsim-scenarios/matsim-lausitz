package org.matsim.dashboards;

import org.matsim.run.analysis.LausitzDrtAnalysis;
import org.matsim.run.scenarios.LausitzScenario;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.*;
import tech.tablesaw.plotly.components.Axis;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows information about an optional policy case, which implements a drt service.
 * It also compares the agents and their trips using the new drt service with their respective trips in the base case.
 */
public class LausitzDrtDashboard implements Dashboard {
	private final String basePath;
	private final String crs;
	private final double scaleFactor;
	private static final String SHARE = "share";
	private static final String ABSOLUTE = "Count [person]";
	private static final String INCOME_GROUP = "incomeGroup";
	private static final String AGE_GROUP = "ageGroup";
	private static final String DESCRIPTION = "... in base and policy case";
	private static final String MAIN_MODE = "main_mode";
	private static final String SOURCE = "source";

	public LausitzDrtDashboard(String basePath, String crs, double scaleFactor) {
		if (!basePath.endsWith("/")) {
			basePath += "/";
		}
		this.basePath = basePath;
		this.crs = crs;
		this.scaleFactor = scaleFactor;
	}

	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Lausitz DRT Dashboard";
		header.description = "Shows statistics about agents, who used the newly implemented drt service " +
			"and compares to the trips of those agents in the base case.";

		String[] args = new ArrayList<>(List.of("--base-path", basePath)).toArray(new String[0]);

		layout.row("first")
			.el(Tile.class, (viz, data) -> {
			viz.dataset = data.compute(LausitzDrtAnalysis.class, "mean_travel_stats.csv", args);
			viz.height = 0.1;
		});

		layout.row("income")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per income group";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_income_groups.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = SHARE;
				viz.columns = List.of(SHARE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per income group";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_income_groups.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "General income distribution";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "all_persons_income_groups.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			});
		layout.row("score")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Mean score per income group";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_mean_score_per_income_group.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = "mean score";
				viz.columns = List.of("mean_score_base", "mean_score_policy");});

		createAgePlots(layout, args);

		layout.row("third")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Modal split (base case)";
				viz.description = "Shows mode of agents in base case, which used the new pt line in the policy case.";

				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				Plotly.DataSet ds = viz.addDataset(data.compute(LausitzDrtAnalysis.class, "drt_persons_base_modal_share.csv", args))
					.constant(SOURCE, "Base Case Mode")
					.aggregate(List.of(MAIN_MODE), SHARE, Plotly.AggrFunc.SUM);

				viz.mergeDatasets = true;
				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
					ds.mapping()
						.name(MAIN_MODE)
						.y(SOURCE)
						.x(SHARE)
				);
			})
			.el(Hexagons.class, (viz, data) -> {

				viz.title = "Pt line agents home locations";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;

				viz.file = data.compute(LausitzDrtAnalysis.class, "drt_persons_home_locations.csv");
				viz.projection = LausitzScenario.CRS;
				viz.addAggregation("home locations", "person", "home_x", "home_y");
			});

		layout.row("modal split")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Modal split in drt service area";

				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				Plotly.DataSet ds = viz.addDataset(data.compute(LausitzDrtAnalysis.class, "mode_share.csv", args))
					.constant(SOURCE, "Simulated")
					.aggregate(List.of(MAIN_MODE), SHARE, Plotly.AggrFunc.SUM);

				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
					ds.mapping()
						.name(MAIN_MODE)
						.y(SOURCE)
						.x(SHARE)
				);
			})
			.el(Plotly.class, (viz, data) -> {

				viz.title = "Modal distance distribution in drt service area";

				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.xAxis(Axis.builder().title("Distance group").build())
					.yAxis(Axis.builder().title(SHARE).build())
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				Plotly.DataSet sim = viz.addDataset(data.compute(LausitzDrtAnalysis.class, "mode_share_per_dist.csv"))
					.constant(SOURCE, "Sim");

				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).build(),
					sim.mapping()
						.name(MAIN_MODE)
						.x("dist_group")
						.y(SHARE)
				);
			});

		layout.row("od-map")
				.el(AggregateOD.class, (viz, data) -> {
					viz.title = "OD flows between DRT areas by DRT";
					viz.shpFile = data.compute(LausitzDrtAnalysis.class, "serviceArea.shp");
//					file naming is a workaround, see LausitzDrtAnalysis.class
					viz.dbfFile = data.compute(LausitzDrtAnalysis.class, "serviceArea1.dbf");
					viz.csvFile = data.compute(LausitzDrtAnalysis.class, "drt_legs_zones_od.csv");
					viz.projection = crs;
					viz.scaleFactor = scaleFactor * 100;
					viz.idColumn = "id";
					viz.lineWidth = 1.;
				});

		createTableLayouts(layout);
	}

	private static void createAgePlots(Layout layout, String[] args) {
		layout.row("age")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per age group";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_age_groups.csv", args);
				viz.x = AGE_GROUP;
				viz.xAxisName = AGE_GROUP;
				viz.yAxisName = SHARE;
				viz.columns = List.of(SHARE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per age group";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_age_groups.csv", args);
				viz.x = AGE_GROUP;
				viz.xAxisName = AGE_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "General age distribution";
				viz.stacked = false;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "all_persons_age_groups.csv", args);
				viz.x = AGE_GROUP;
				viz.xAxisName = AGE_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			});
	}

	private static void createTableLayouts(Layout layout) {
		layout.row("fourth")
			.el(Table.class, (viz, data) -> {
				viz.title = "Executed scores";
				viz.description = DESCRIPTION;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_executed_score.csv");
				viz.showAllRows = true;
			})
			.el(Table.class, (viz, data) -> {
				viz.title = "Travel times";
				viz.description = DESCRIPTION;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_trav_time.csv");
				viz.showAllRows = true;
			})
			.el(Table.class, (viz, data) -> {
				viz.title = "Travel distances";
				viz.description = DESCRIPTION;
				viz.dataset = data.compute(LausitzDrtAnalysis.class, "drt_persons_traveled_distance.csv");
				viz.showAllRows = true;
			});
	}
}
