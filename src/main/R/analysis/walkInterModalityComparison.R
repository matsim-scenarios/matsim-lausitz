library(tidyverse)
library(lubridate)

# This class analyzes and compares intermodal trips walk<->pt when also implementing a drt service.
# drt is configured as an intermodal mode to access/egress pt. 
# So is walk. But the walk intermodality settings were set incorrectly.
# Hence, the following comparison. -sm0625

trips <- read.csv(file="Y:/net/ils/matsim-lausitz/walk-intermodal-range-test/output-2-ruhland-bhf-spremberg-bhf/2-ruhland-bhf-spremberg-bhf.output_trips.csv.gz", sep=";")
trips_correct_walk_intermodality <- read.csv(file="Y:/net/ils/matsim-lausitz/walk-intermodal-range-test/output-2-ruhland-bhf-spremberg-bhf-intermodal-fix/2-ruhland-bhf-spremberg-bhf-intermodal-fix.output_trips.csv.gz", sep=";")
trips_correct_walk_intermodality_10km <- read.csv(file="Y:/net/ils/matsim-lausitz/walk-intermodal-range-test/output-2-ruhland-bhf-spremberg-bhf-intermodal-fix-10km/2-ruhland-bhf-spremberg-bhf-intermodal-fix-10km.output_trips.csv.gz", sep=";")

pt <- "pt"
drt <- "drt"

trips_pt <- trips %>% 
  filter(str_detect(modes, pt)) %>% 
  filter(!str_detect(modes, drt)) %>% 
  mutate(trav_time_s=seconds(hms(trav_time)))
trips_correct_walk_intermodality_pt <- trips_correct_walk_intermodality %>% 
  filter(str_detect(modes, pt)) %>% 
  filter(!str_detect(modes, drt)) %>% 
  mutate(trav_time_s=seconds(hms(trav_time)))
trips_correct_walk_intermodality_pt_10km <- trips_correct_walk_intermodality_10km %>%
  filter(str_detect(modes, pt)) %>%
  filter(!str_detect(modes, drt)) %>%
  mutate(trav_time_s=seconds(hms(trav_time)))

# there should be more intermodal trips with corrected walk intermodality params -> positive diff
diff_walk_pt_intermodal_trips <- as.integer(nrow(trips_correct_walk_intermodality_pt) - nrow(trips_pt))
diff_walk_pt_intermodal_trips_10km <- as.integer(nrow(trips_correct_walk_intermodality_pt_10km) - nrow(trips_pt))

# mean trav_dur should increase with corrected walk intermodality
mean_trav_dur_incorrect_walk_intermodality <- round(mean(trips_pt$trav_time_s))
mean_trav_dur_correct_walk_intermodality <- round(mean(trips_correct_walk_intermodality_pt$trav_time_s))
mean_trav_dur_correct_walk_intermodality_10km <- round(mean(trips_correct_walk_intermodality_pt_10km$trav_time_s))

# mean trav_dist should increase with corrected walk intermodality
mean_trav_dist_incorrect_walk_intermodality <- round(mean(trips_pt$traveled_distance))
mean_trav_dist_correct_walk_intermodality <- round(mean(trips_correct_walk_intermodality_pt$traveled_distance))
mean_trav_dist_correct_walk_intermodality_10km <- round(mean(trips_correct_walk_intermodality_pt_10km$traveled_distance))
