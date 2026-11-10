library(tidyverse)
library(kableExtra)

od_trips <- read_csv(file="C:/Users/Simon/Desktop/wd/2025-08-11/od-analysis-hoy-surrounding-cities.csv")

################ prepare datasets for different OD relation between zones #######################################################
hoy_to_cott <- od_trips %>% 
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "cottbus"))
cott_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "cottbus") & str_detect(to_area, "hoyerswerda"))
hoy_to_spremberg <- od_trips %>%
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "spremberg"))
spremberg_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "spremberg") & str_detect(to_area, "hoyerswerda"))
hoy_to_weisswasser <- od_trips %>%
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "weisswasser"))
weisswasser_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "weisswasser") & str_detect(to_area, "hoyerswerda"))
hoy_to_bautzen <- od_trips %>%
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "bautzen"))
bautzen_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "bautzen") & str_detect(to_area, "hoyerswerda"))
hoy_to_kamenz <- od_trips %>%
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "kamenz"))
kamenz_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "kamenz") & str_detect(to_area, "hoyerswerda"))
hoy_to_bernsdorf <- od_trips %>%
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "bernsdorf"))
bernsdorf_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "bernsdorf") & str_detect(to_area, "hoyerswerda"))
hoy_to_senftenberg <- od_trips %>%
  filter(str_detect(from_area, "hoyerswerda") & str_detect(to_area, "senftenberg"))
senftenberg_to_hoy <- od_trips %>%
  filter(str_detect(from_area, "senftenberg") & str_detect(to_area, "hoyerswerda"))

od_datasets <- list(hoy_to_cott, cott_to_hoy, hoy_to_spremberg, spremberg_to_hoy, hoy_to_weisswasser, weisswasser_to_hoy,
                    hoy_to_bautzen, bautzen_to_hoy, hoy_to_kamenz, kamenz_to_hoy, hoy_to_bernsdorf, bernsdorf_to_hoy,
                    hoy_to_senftenberg, senftenberg_to_hoy)

overview <- data.frame(
  from = character(),
  to = character(),
  main_mode = character(),
  n_trips   = integer(),
  share = double(),
  stringsAsFactors = FALSE
)

for (d in od_datasets) {
  modes <- unique(d$main_mode)
  from <- unique(d$from_area)
  to <- unique(d$to_area)
  count_rows_general <- d %>% nrow()

  # add row for all modes
  overview <- rbind(
    overview,
    data.frame(from = from, to = to, main_mode = "all", n_trips = count_rows_general * 10, share = count_rows_general/count_rows_general, stringsAsFactors = FALSE))

  # loop through modes
  for (m in modes) {
    count_rows <- d %>%
      filter(main_mode == m) %>%
      nrow()

    overview <- rbind(overview, data.frame(from = from, to = to, main_mode = m, n_trips = count_rows * 10, share = count_rows/count_rows_general, stringsAsFactors = FALSE))
  }
}

overview <- overview %>% 
  rename(From = 'from',
         To = 'to',
         'Main mode' = 'main_mode',
         Trips = 'n_trips',
         Share = 'share') %>% 
  mutate(Share = round(Share, digits = 2))

latex_table <- overview %>% 
  kable(format = "latex", caption = "gls{OD}~relations between different zones. For the zones, see Figure XX. Zone cottbus_extended is not a gls{DRT}~service area, but a zone created for this gls{OD}-analysis with a radius of approx. $16$~km around the city.",
        label = "tab:od-analysis")
latex_table

pt <- overview %>% 
  filter(`Main mode`== "pt")
pt_mean_share <- mean(pt$Share)

car <- overview %>% 
  filter(`Main mode`== "car")
car_mean_share <- mean(car$Share)





