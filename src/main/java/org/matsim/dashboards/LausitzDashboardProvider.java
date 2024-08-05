package org.matsim.dashboards;

import org.matsim.core.config.Config;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.dashboard.TripDashboard;

import java.util.List;

/**
 * Dashboards for the Lausitz scenario.
 */
public class LausitzDashboardProvider implements DashboardProvider {
	@Override
	public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {
		return List.of(new TripDashboard(
			"lausitz_mode_share.csv",
			"lausitz_mode_share_per_dist.csv",
			"lausitz_mode_users.csv")
			.withGroupedRefData("lausitz_mode_share_per_group_dist_ref.csv", "age", "economic_status")
			.withDistanceDistribution("lausitz_mode_share_distance_distribution.csv")
		);
	}
}
