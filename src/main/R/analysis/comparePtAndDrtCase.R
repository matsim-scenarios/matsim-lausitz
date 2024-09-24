library(tidyverse)
library(lubridate)

ptPersons <- read.csv(file="C:/Users/Simon/Desktop/wd/2024-09-23/pt-persons.csv")

ptCaseTrips <- read.csv2(file="Y:/net/ils/matsim-lausitz/caseStudies/v1.1/pt-case-study/output/lausitz-pt-case/lausitz-10ct.output_trips.csv.gz")
drtCaseTrips <- read.csv2(file="Y:/net/ils/matsim-lausitz/caseStudies/v1.1/drt-case-study/output-1km-ptRadius/lausitz-drt-case-study.output_trips.csv.gz")

ptPersons <- ptPersons %>% 
  mutate(person=as.character(person),
         time = as.double(time))

filteredPtCase <- ptCaseTrips %>% 
  filter(person %in% ptPersons$person) %>% 
  left_join(ptPersons, by="person") %>% 
  mutate(dep_s = as.numeric(hms(dep_time)),
         trav_s = as.numeric(hms(trav_time))) %>% 
  filter(time >= dep_s & time <= dep_s + trav_s)

meanTravTimePtCase <- mean(filteredPtCase$trav_s)
meanTravDistPtCase <- mean(filteredPtCase$traveled_distance)

filteredDrtCase <- drtCaseTrips %>% 
  filter(person %in% ptPersons$person) %>% 
  left_join(ptPersons, by="person") %>% 
  mutate(dep_s = as.numeric(hms(dep_time)),
         trav_s = as.numeric(hms(trav_time))) %>% 
  filter(time >= dep_s & time <= dep_s + trav_s)

meanTravTimeDrtCase <- mean(filteredDrtCase$trav_s)
meanTravDistDrtCase <- mean(filteredDrtCase$traveled_distance)

comparison <- inner_join(filteredPtCase, filteredDrtCase, by="person", suffix = c(".ptCase", ".drtCase")) %>% 
  select(person, trip_id.ptCase, trav_s.ptCase, traveled_distance.ptCase, main_mode.ptCase,
         trip_id.drtCase, trav_s.drtCase, traveled_distance.drtCase, main_mode.drtCase) %>% 
  mutate(delta_trav_s = trav_s.ptCase - trav_s.drtCase,
         delta_trav_dist = traveled_distance.ptCase - traveled_distance.drtCase)

summary <- comparison %>% 
  summarize(mean_trav_s_ptCase=mean(trav_s.ptCase),
            mean_trav_s_drtCase=mean(trav_s.drtCase),
            mean_trav_dist_ptCase=mean(traveled_distance.ptCase),
            mean_trav_dist_drtCase=mean(traveled_distance.drtCase),
            mean_trav_s_delta=mean(delta_trav_s),
            mean_trav_dist_delta=mean(delta_trav_dist)) %>% 
  mutate(mean_trav_min_ptCase=mean_trav_s_ptCase / 60,
         mean_trav_min_drtCase=mean_trav_s_drtCase / 60)

write.csv(summary, file="C:/Users/Simon/Desktop/wd/2024-09-23/lausitz_ptCase_drtCase_summary.csv", quote = FALSE, row.names = FALSE)

# Reshape the data to a long format for easy plotting
df_long <- summary %>%
  pivot_longer(
    cols = c(mean_trav_min_ptCase, mean_trav_min_drtCase, 
             mean_trav_dist_ptCase, mean_trav_dist_drtCase),
    names_to = c("metric", "Case"),
    names_pattern = "(mean_trav_[^_]+)_(ptCase|drtCase)"
  )

# Create the barplot for mean_trav_min and mean_trav_dist
ggplot(df_long, aes(x = Case, y = value, fill = Case)) +
  geom_bar(stat = "identity", position = "dodge") +
  facet_wrap(~ metric, scales = "free_y") +
  labs(title = "Comparison of mean_trav_min and mean_trav_dist by Case",
       x = "Case", y = "Value") +
  theme_minimal()
  





