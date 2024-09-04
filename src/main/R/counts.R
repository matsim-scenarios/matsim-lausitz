##### Analysis of linkstats and counts based on BASt data
devtools::install_github("matsim-vsp/matsim-r",ref="counts")

library(matsim)
library(tidyverse)

COUNTS <- "../../public-svn/matsim/scenarios/countries/de/lausitz/input/v1.0/lausitz-v1.0-counts-car-bast.xml.gz"
NETWORK <- "../../public-svn/matsim/scenarios/countries/de/lausitz/input/v1.0/lausitz-v1.0-network-with-pt.xml.gz"

linkstats <- readLinkStats(runId = "v1.0-uncalibrated", file = "Y:/matsim-lausitz/qa/output/lausitz-25pct.output_linkstats.csv.gz", sampleSize = 1)

counts <- readCounts(COUNTS)
network <- loadNetwork(NETWORK)

join <- mergeCountsAndLinks(counts = counts, linkStats  = list(linkstats), network = network,
                            networkModes = c("car"), aggr_to = "day")

#### VIA-styled scatterplot ####

FILE_DIR = "../../shared-svn/projects/DiTriMo/data/commuters-by-town"

createCountScatterPlot(joinedFrame = join)
ggsave(filename = paste0(FILE_DIR, "Traffic_Count_Scatterplot_with_freight.jpg"))

#### Analysis of DTV distribution ####
join.dtv.distribution <- processLinkStatsDtvDistribution(joinedFrame = join, to = 50000)

ggplot(join.dtv.distribution, aes(x = traffic_bin, y = share, fill = type)) +
  
  geom_col() +
  
  facet_grid(src ~ type) +
  
  labs(x = "Daily traffic volume", y = "Share") +
  
  theme_bw() +
  
  theme(legend.position = "none", axis.text.x = element_text(angle = 90))

ggsave(filename = paste0(FILE_DIR, "Traffic_volume_distribution_by_road_type_with_freight.jpg"))
rm(join.dtv.distribution)


#### Analysis of Estimation Quality ####

join.est.quality <- processDtvEstimationQuality(joinedFrame = join, aggr = F) %>%
  filter(!type %in% c("residential", "unclassified", NA))

ggplot(join.est.quality, aes(estimation, share, fill = type)) +
  
  geom_col() +
  
  labs(y = "Share", x = "Quality category") +
  
  facet_grid(src ~ type) +
  
  theme_bw() +
  
  theme(legend.position = "none", axis.text.x = element_text(angle = 90))

rm(join.est.quality)
ggsave(filename = paste0(FILE_DIR, "Estimation_quality_by_road_type_with_freight.jpg"))


#### network plot ####

library(tmap)
library(tmaptools)
library(OpenStreetMap)
library(sf)

link.geom <- join %>%
  left_join(network$links, by = c("loc_id" = "id")) %>%
  mutate(geom = sprintf("LINESTRING(%s %s, %s %s)", x.from, y.from, x.to, y.to)) %>%
  st_as_sf(crs = 25832, wkt = "geom") %>%
  transmute(loc_id, type.x, rel_vol = volume / count, geom)

tmap_mode("view")

tm_shape(shp = link.geom) +
  tm_lines(col = "estimation", style = "cont", lwd = 3.5, palette = c("red", "green", "blue"))

tm_shape(shp = link.geom) +
  tm_lines(col = "rel_vol", style = "cont", lwd = 5, palette = c("red", "yellow", "green"), breaks = c(0, 0.05, 0.8, 2))
