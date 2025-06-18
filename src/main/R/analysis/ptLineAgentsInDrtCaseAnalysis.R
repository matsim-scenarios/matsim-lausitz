library(tidyverse)
library(lubridate)
library(data.table)
library(ggokabeito)
library(optparse)

# an analysis which compares the trips from agents in the pt line case (pt line users only)
# to their corresponding trips in a drt case

########################################## input params #########################################################################

option_list <- list(
  make_option(c("-r", "--runDir"), type="character", default=NULL,
              help="Path to drt run directory. Avoid using '\', use '/' instead.", metavar="character"))

opt_parser <- OptionParser(option_list=option_list)
opt <- parse_args(opt_parser)

run_dir <- opt$runDir
run_dir_fixed <- gsub("\\\\", "/", run_dir)
setwd(run_dir_fixed)

print(paste("Running analysis on run dir", getwd()))

trips_path <- list.files(path=run_dir_fixed, pattern="output_trips\\.csv\\.gz$", full.names = TRUE)
persons_path <- list.files(path=run_dir_fixed, pattern="output_persons\\.csv\\.gz$", full.names = TRUE)


############################################## get the data ##################################################################

trips_drt_case <- read.csv2(file=trips_path)
trips_pt_line_case <- read_csv2(file="https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/01_pt-case-study/lausitz-pt-case.output_trips.csv.gz")

persons_drt_case <- read.csv2(file=persons_path)
persons_pt_line_case <- read_csv2(file="https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/01_pt-case-study/lausitz-pt-case.output_persons.csv.gz")

pt_line_users <- read_csv(file="https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/projects/DiTriMo/01_pt-case-study/analysis/analysis/pt_persons.csv") %>% 
  mutate(person=as.character(person))

##################################################### persons analysis ######################################################

persons_drt_case_filtered <- persons_drt_case %>% 
  filter(person %in% pt_line_users$person)

persons_pt_line_case_filtered <- persons_pt_line_case %>% 
  filter(person %in% pt_line_users$person)

persons_joined <- persons_pt_line_case_filtered %>% 
  mutate(executed_score_pt_line_case=executed_score) %>% 
  select(person, executed_score_pt_line_case) %>% 
  left_join(persons_drt_case %>% 
              mutate(executed_score_drt_case=executed_score) %>% 
              select(person, executed_score_drt_case, age, home_x, home_y, income), by="person") %>% 
  mutate(executed_score_pt_line_case=as.double(executed_score_pt_line_case),
         executed_score_drt_case=as.double(executed_score_drt_case),
         home_x=as.double(home_x),
         home_y=as.double(home_y),
         income=as.double(income))

mean_score_pt_line_case <- round(mean(persons_joined$executed_score_pt_line_case), digits=2)
mean_score_drt_case <- round(mean(persons_joined$executed_score_drt_case), digits=2)


########################################### trips analysis #################################################

trips_pt_line_case <- trips_pt_line_case %>% 
  filter(main_mode != "longDistanceFreight" & !str_detect(main_mode, "truck")) %>% 
  mutate(dep_time=as.numeric(seconds(hms(dep_time))),
         trav_time=as.numeric(seconds(hms(trav_time)))) %>% 
  mutate(arr_time=dep_time + trav_time)

trips_drt_case <- trips_drt_case %>% 
  filter(main_mode != "longDistanceFreight" & !str_detect(main_mode, "truck")) %>% 
  mutate(dep_time=as.numeric(seconds(hms(dep_time))),
         trav_time=as.numeric(seconds(hms(trav_time)))) %>% 
  mutate(arr_time=dep_time + trav_time)

match_trips_to_enter_times <- function(trips_df, users_df) {
  # Convert to data.table
  dt_trips <- as.data.table(trips_df)
  dt_users <- as.data.table(users_df)
  
  setkey(dt_trips, person, dep_time, arr_time)
  
  # match where dep_time <= time <= arr_time for same person
  result <- dt_trips[dt_users, 
                     on = .(person, dep_time <= time, arr_time >= time), 
                     nomatch = 0]
  
  # Convert back to regular data.frame
  return(as.data.frame(result))
}

# use data.table library to find trips with pt line based on pt line vehicle enter time
pt_line_trips <- match_trips_to_enter_times(trips_pt_line_case, pt_line_users)

# find corresponding trips in drt case trips table
corresponding_trips_in_drt_case <- trips_drt_case %>% 
  filter(trip_id %in% pt_line_trips$trip_id)

mean_trav_time_pt_line_case <- round(mean(pt_line_trips$trav_time))
mean_trav_time_drt_case <- round(mean(corresponding_trips_in_drt_case$trav_time))

mean_trav_dist_pt_line_case <- round(mean(pt_line_trips$traveled_distance))
mean_trav_dist_drt_case <- round(mean(corresponding_trips_in_drt_case$traveled_distance))

modal_split_corresponding_trips_in_drt_case <- corresponding_trips_in_drt_case %>% 
  count(main_mode) %>% 
  mutate(percentage = n / sum(n) * 100,
         legend_label = paste0(main_mode, " (", round(percentage, 1), "%)"))

plot_modal_split_drt_case <- ggplot(modal_split_corresponding_trips_in_drt_case, aes(x = "", y = n, fill = legend_label)) +
  geom_bar(stat = "identity", width = 1, color = "black") +
  coord_polar("y") +
  theme_void() +
  labs(title = "Modal Split for corresponding trips to trips with pt line (in drt case)", fill = "Main Mode") +
  scale_fill_okabe_ito()

############################################ pt line trips -> drt trips only ##############################################################

# get pt line trips which changed to drt only
corresponding_trips_drt_only <- corresponding_trips_in_drt_case %>% 
  filter(str_detect(modes, "drt"))

# find corresponding pt line trips
pt_line_trips_corresponding_drt <- pt_line_trips %>% 
  filter(trip_id %in% corresponding_trips_drt_only$trip_id)

# calc mean trav time and dist for drt / corr trips
mean_trav_time_pt_line_case_corr_drt_trips <- round(mean(pt_line_trips_corresponding_drt$trav_time))
mean_trav_time_drt_case_drt_trips <- round(mean(corresponding_trips_drt_only$trav_time))

mean_trav_dist_pt_line_case_corr_drt_trips <- round(mean(pt_line_trips_corresponding_drt$traveled_distance))
mean_trav_dist_drt_case_drt_trips <- round(mean(corresponding_trips_drt_only$traveled_distance))

# filter persons to compare score of pt line -> drt agents
persons_joined_drt <- persons_joined %>% 
  filter(person %in% corresponding_trips_drt_only$person)

mean_score_pt_line_case_drt_persons <- round(mean(persons_joined_drt$executed_score_pt_line_case), digits=2)
mean_score_drt_case_drt_persons <- round(mean(persons_joined_drt$executed_score_drt_case), digits=2)


df <- data.frame(
  parameter = c("mean_score_pt_line_case_util", "mean_score_drt_case_util", "mean_trav_time_pt_line_case_s", 
                "mean_trav_time_drt_case_s", "mean_trav_dist_pt_line_case_m", "mean_trav_dist_drt_case_m", 
                "mean_score_pt_line_case_drt_persons_util", "mean_score_drt_case_drt_persons_util",
                "mean_trav_time_pt_line_case_corr_drt_trips_s", "mean_trav_time_drt_case_drt_trips_s", 
                "mean_trav_dist_pt_line_case_corr_drt_trips_m", "mean_trav_dist_drt_case_drt_trips_m"),
  
  value = c(mean_score_pt_line_case, mean_score_drt_case, mean_trav_time_pt_line_case, 
            mean_trav_time_drt_case, mean_trav_dist_pt_line_case, mean_trav_dist_drt_case, 
            mean_score_pt_line_case_drt_persons, mean_score_drt_case_drt_persons,
            mean_trav_time_pt_line_case_corr_drt_trips, mean_trav_time_drt_case_drt_trips, 
            mean_trav_dist_pt_line_case_corr_drt_trips, mean_trav_dist_drt_case_drt_trips)
)

write.csv(df, file="mean_stats_pt_line_drt_cases_comparison.csv")
write.csv(persons_joined, file="score_comparison_pt_line_drt_cases.csv")
ggsave("modal_split_of_trips_corresponding_to_pt_line_trips.pdf", plot_modal_split_drt_case, dpi = 500, w = 12, h = 9)

print("mean comparison stats, score comparison for each agent and modal split in drt case written to", getwd())


