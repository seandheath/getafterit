#include <pebble.h>

#define STAT_SLOTS 6
#define MAP_SLOTS 5  // top left, top right, bottom left, bottom right, top centre
#define MAP_SCREENS 3
#define MAP_W 200
#define MAP_H 228
#define ZOOM_MIN 14
#define ZOOM_MAX 17
#define CORNER_MARGIN 4  // corner text hugs the screen's outer edges, growing inward
#define BOX_H 56
#define CENTRE_W 64  // the map's top centre field
#define CADENCE_WINDOW_S 15
#define GPS_STALE_S 10
#define HEADER_H 24
#define ACTION_BAR_W 30
// Companion (Android) command keys; fixed numbers outside the generated message keys.
#define CMD_START_KEY 1
#define CMD_STOP_KEY 2
#define ZOOM_KEY 3
#define SCROLL_X_KEY 4  // map dragged away from the runner, screen pixels
#define SCROLL_Y_KEY 5
#define MODE_KEY 6      // mode picked on the watch
#define HELLO_KEY 7     // watchapp ready to receive; the companion replies with settings and run state
#define PAUSED_KEY 8    // 1 while the run is paused; sent with every message during a run
#define DRAG_MIN_PX 6   // smaller finger movements are taps, not drags
// Companion → watch keys. Lengths in meters, pace in seconds per km.
#define CO_RUN_STARTED 110
#define CO_RUN_STOPPED 111
#define CO_PACE 120
#define CO_MOVING 122
#define CO_DIST 123
#define CO_GAIN 124
#define CO_LOSS 125
// Companion map: GPS fix, course progress and the map image.
#define CO_FIX 126
#define CO_ELEV 127
#define CO_REMAIN 128      // meters to the end of the course, -1 without one
#define CO_OFF_COURSE 129
#define CO_IMG_OFFSET 130
#define CO_IMG_DATA 131
#define CO_MARKER_X 132
#define CO_MARKER_Y 133
#define CO_MARKER_DIR 134
#define CO_ERROR 135
#define CO_HR 136          // chest-strap heart rate from the companion, bpm
#define STRAP_HR_FRESH_S 30  // keep strap HR through brief dropouts before falling back to the wrist
// The companion sends settings as soon as the watchapp opens; silence after
// this long means it isn't installed (or can't be reached).
#define COMPANION_WAIT_MS 8000
// Settings from the companion: int32 scalars, slot lists as bytes.
#define SET_MILES_KEY 140
#define SET_BACKLIGHT_KEY 141
#define SET_MAX_HR_KEY 142
#define SET_OVERLAY_KEY 143
#define SET_MAP_SLOTS_KEY 144  // every map activity's screens, MAP_MODES x MapScreens
#define SET_MODE_KEY 146
#define SET_STATS_SLOTS_KEY 147  // Run's stats screens
#define SET_MODES_KEY 150        // bit per Mode: which ones Select cycles through
#define RUN_KEY 1
#define SETTINGS_KEY 5  // 2-4 held earlier layouts
#define SUMMARY_ROW_H 36

typedef enum {
  STAT_NONE,
  STAT_DIST,
  STAT_ELAPSED,
  STAT_MOVING,
  STAT_PACE,
  STAT_AVG_PACE,
  STAT_HR,
  STAT_AVG_HR,
  STAT_MAX_HR,
  STAT_HR_ZONE,
  STAT_ELEV,
  STAT_GAIN,
  STAT_LOSS,
  STAT_REMAIN,
  STAT_ETA,
  STAT_LAP_TIME,
  STAT_LAP_PACE,
  STAT_CADENCE,
  STAT_CLOCK,
  STAT_BATTERY,
  STAT_COUNT,
} Stat;

// The activities picked with Select before starting. Every one but Workout is
// recorded by the companion; the map ones keep their order, MAP_MODES of them.
typedef enum {
  MODE_RUN,          // stats screens only
  MODE_RUN_TRACK,    // map, following the phone's loaded course
  MODE_RUN_RECORD,   // map and breadcrumb, no course
  MODE_HIKE_TRACK,
  MODE_HIKE_RECORD,
  MODE_WORKOUT,      // heart rate and time, on the watch only
  MODE_COUNT,
} Mode;

static const char *MODE_NAMES[] = {"RUN", "RUN TRACK", "RECORD RUN", "HIKE TRACK", "RECORD HIKE", "WORKOUT"};
#define MAP_MODES 4
// Run's stats screens cycle with the same code as the map screens.
#define STATS_SCREENS MAP_SCREENS

typedef enum {
  OVERLAY_NONE,
  OVERLAY_BAR,      // slots 0-1 in a bar along the bottom
  OVERLAY_CORNERS,  // slots 0-3 in the corners
} Overlay;

// A map activity's own fields: up to three map screens and a stats page.
// Only bytes, so an array of them is exactly what the settings message carries.
typedef struct {
  uint8_t map_slots[MAP_SCREENS][MAP_SLOTS];
  uint8_t stat_slots[STAT_SLOTS];
} MapScreens;

typedef struct {
  bool miles;
  bool backlight;  // keep the backlight on while the app is open
  uint8_t max_hr;
  uint8_t overlay;
  MapScreens maps[MAP_MODES];  // from MODE_RUN_TRACK on
  uint8_t stats_slots[STATS_SCREENS][STAT_SLOTS];
  uint8_t mode;
  uint8_t modes;  // bit per Mode
} Settings;

#define TRACK_SCREENS {                                       \
  .map_slots = {                                              \
    {STAT_DIST, STAT_ELAPSED, STAT_PACE, STAT_HR, STAT_CLOCK},          \
    {STAT_ELEV, STAT_GAIN, STAT_REMAIN, STAT_ETA, STAT_CLOCK},          \
    {STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE},            \
  },                                                          \
  .stat_slots = {STAT_DIST, STAT_ELAPSED, STAT_PACE, STAT_AVG_PACE, STAT_HR, STAT_AVG_HR}, \
}
#define RECORD_SCREENS {                                      \
  .map_slots = {                                              \
    {STAT_DIST, STAT_ELAPSED, STAT_PACE, STAT_HR, STAT_CLOCK},          \
    {STAT_ELEV, STAT_GAIN, STAT_MOVING, STAT_AVG_PACE, STAT_CLOCK},     \
    {STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE},            \
  },                                                          \
  .stat_slots = {STAT_DIST, STAT_ELAPSED, STAT_PACE, STAT_AVG_PACE, STAT_HR, STAT_AVG_HR}, \
}

static Settings s_settings = {
  .miles = true,
  .backlight = false,
  .max_hr = 190,
  .overlay = OVERLAY_CORNERS,
  .maps = {TRACK_SCREENS, RECORD_SCREENS, TRACK_SCREENS, RECORD_SCREENS},
  .stats_slots = {
    {STAT_DIST, STAT_ELAPSED, STAT_PACE, STAT_AVG_PACE, STAT_HR, STAT_AVG_HR},
    {STAT_LAP_TIME, STAT_LAP_PACE, STAT_CADENCE, STAT_MOVING, STAT_GAIN, STAT_CLOCK},
    {STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE, STAT_NONE},
  },
  .mode = MODE_RUN,
  .modes = (1 << MODE_COUNT) - 1,
};

static bool map_mode(void) {
  return s_settings.mode >= MODE_RUN_TRACK && s_settings.mode <= MODE_HIKE_RECORD;
}

static bool hiking(void) {
  return s_settings.mode == MODE_HIKE_TRACK || s_settings.mode == MODE_HIKE_RECORD;
}

// Only meaningful in a map mode.
static const MapScreens *map_screens(void) {
  return &s_settings.maps[s_settings.mode - MODE_RUN_TRACK];
}

static struct {
  bool started;
  bool running;
  int elapsed_s;
  int32_t dist_m;
  int32_t moving_s;
  int32_t pace_s_km;  // 0 when stopped
  int32_t pace_shown; // current pace as displayed, seconds per mile or km; 0 for "--"
  bool has_elev;
  int32_t elev_m;
  int32_t gain_m;
  int32_t loss_m;
  bool has_course;
  int32_t remain_m;
  bool off_course;
  time_t last_fix;
  int hr;
  int strap_hr;
  time_t strap_hr_at;  // when strap_hr arrived; 0 for never
  int hr_max;
  uint32_t hr_sum;
  uint32_t hr_count;
  int lap_start_s;
  int32_t lap_start_m;
  int32_t steps[CADENCE_WINDOW_S];
  int steps_i;
  char error[64];
  int32_t scroll_x;  // how far the view is dragged from the runner; 0,0 follows them
  int32_t scroll_y;
  bool dragging;
  GPoint drag;       // current finger movement, applied to the shown image
  GPoint shift;      // drags the phone hasn't redrawn yet; cleared when a new image lands
  bool summary;      // run stopped, showing its stats until Back quits
  bool picking;      // the activity list shown when the app opens
  uint8_t pick;      // highlighted Mode in that list
  int summary_top;   // first row shown
  bool map_page;
  int screen;  // index into the current mode's map or stats screens
  int zoom;
  bool has_marker;
  GPoint marker;
  int marker_dir;  // degrees clockwise from image up, -1 when unknown
} s;

// What survives the app closing mid-run; reopening the app resumes it.
typedef struct {
  bool running;
  int32_t elapsed_s;
  int32_t dist_m;
  int32_t moving_s;
  int32_t gain_m;
  int32_t loss_m;
  int32_t hr_max;
  uint32_t hr_sum;
  uint32_t hr_count;
  int32_t lap_start_s;
  int32_t lap_start_m;
  int32_t saved_at;
} RunSave;

static bool s_companion_seen;
static bool s_strap_hr;  // strap readings are fresh, so the wrist sensor runs at its normal rate
// Screen dots and the header show briefly at run start and on screen change.
#define INDICATOR_MS 3000
static bool s_indicator;
static AppTimer *s_indicator_timer;
static bool s_companion_missing;
static time_t s_companion_at;  // last message from the companion
// After this long without hearing from the companion, nudge it: a companion
// restarted while the watchapp stays open doesn't know the app is open.
#define COMPANION_PING_S 15
// Long-press time for zoom and start/stop; the hold ring fills over it.
#define HOLD_MS 500
#define STOP_HOLD_MS 2000  // holding Up this long on the paused screen stops and saves
#define HOLD_SHOW_MS 120  // a quick tap ends sooner, so taps don't flash the ring
static ButtonId s_hold_button = NUM_BUTTONS;  // NUM_BUTTONS when nothing is held
static uint64_t s_hold_start_ms;
static uint32_t s_hold_ms = HOLD_MS;  // how long the current hold takes
static AppTimer *s_hold_timer;

static Window *s_window;
static Layer *s_layer;
// The phone streams a new map into the back buffer while the front one is shown.
static GBitmap *s_front;
static GBitmap *s_back;
static bool s_has_image;
static GPath *s_arrow;
static GPath *s_play;
static GPath *s_heart;  // the heart's point; draw_heart adds the two lobes
static const GPathInfo ARROW_INFO = {3, (GPoint[]){{0, -15}, {6, -5}, {-6, -5}}};
static const GPathInfo PLAY_INFO = {3, (GPoint[]){{-6, -8}, {8, 0}, {-6, 8}}};
static const GPathInfo HEART_INFO = {3, (GPoint[]){{-11, -1}, {11, -1}, {0, 11}}};

static int32_t unit_m(void) {
  return s_settings.miles ? 1609 : 1000;
}

static void format_duration(char *buf, size_t n, int secs) {
  if (secs >= 3600) {
    snprintf(buf, n, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60);
  } else {
    snprintf(buf, n, "%d:%02d", secs / 60, secs % 60);
  }
}

static void format_pace(char *buf, size_t n, int32_t s_per_km) {
  if (s_per_km <= 0 || s_per_km >= 3600) {
    snprintf(buf, n, "--");
    return;
  }
  format_duration(buf, n, s_per_km * unit_m() / 1000);
}

static void format_distance(char *buf, size_t n, int32_t meters) {
  int32_t hundredths = meters * 100 / unit_m();
  snprintf(buf, n, "%ld.%02ld", (long)(hundredths / 100), (long)(hundredths % 100));
}

static void format_int(char *buf, size_t n, int value, bool known) {
  if (known) {
    snprintf(buf, n, "%d", value);
  } else {
    snprintf(buf, n, "--");
  }
}

// 1-5 by percent of max HR: under 60, 60s, 70s, 80s, 90 and over.
static int hr_zone(void) {
  int pct = s.hr * 100 / s_settings.max_hr;
  return pct < 60 ? 1 : pct < 70 ? 2 : pct < 80 ? 3 : pct < 90 ? 4 : 5;
}

static int cadence_spm(void) {
  int newest = s.steps[(s.steps_i + CADENCE_WINDOW_S - 1) % CADENCE_WINDOW_S];
  int oldest = s.steps[s.steps_i];
  int spm = (newest - oldest) * 60 / (CADENCE_WINDOW_S - 1);
  return spm > 0 ? spm : 0;  // the step count resets at midnight
}

static void format_stat(Stat stat, const char **label, char *value, size_t n) {
  const char *pace_label = s_settings.miles ? "PACE /MI" : "PACE /KM";
  switch (stat) {
    case STAT_NONE:
      *label = "";
      value[0] = '\0';
      return;
    case STAT_DIST:
      *label = s_settings.miles ? "DIST MI" : "DIST KM";
      format_distance(value, n, s.dist_m);
      return;
    case STAT_ELAPSED:
      *label = "TIME";
      format_duration(value, n, s.elapsed_s);
      return;
    case STAT_MOVING:
      *label = "MOVING";
      format_duration(value, n, s.moving_s);
      return;
    case STAT_PACE:
      *label = pace_label;
      if (s.pace_shown) {
        format_duration(value, n, s.pace_shown);
      } else {
        snprintf(value, n, "--");
      }
      return;
    case STAT_AVG_PACE:
      *label = s_settings.miles ? "AVG /MI" : "AVG /KM";
      format_pace(value, n, s.dist_m > 0 ? s.elapsed_s * 1000 / s.dist_m : 0);
      return;
    case STAT_HR:
      *label = "HR";
      format_int(value, n, s.hr, s.hr > 0);
      return;
    case STAT_AVG_HR:
      *label = "AVG HR";
      format_int(value, n, s.hr_count ? (int)(s.hr_sum / s.hr_count) : 0, s.hr_count > 0);
      return;
    case STAT_MAX_HR:
      *label = "MAX HR";
      format_int(value, n, s.hr_max, s.hr_max > 0);
      return;
    case STAT_HR_ZONE: {
      *label = "ZONE";
      if (s.hr > 0) {
        snprintf(value, n, "Z%d", hr_zone());
      } else {
        snprintf(value, n, "--");
      }
      return;
    }
    case STAT_ELEV:
      *label = s_settings.miles ? "ELEV FT" : "ELEV M";
      format_int(value, n, s_settings.miles ? s.elev_m * 3281 / 1000 : s.elev_m, s.has_elev);
      return;
    case STAT_GAIN:
      *label = s_settings.miles ? "GAIN FT" : "GAIN M";
      format_int(value, n, s_settings.miles ? s.gain_m * 3281 / 1000 : s.gain_m, true);
      return;
    case STAT_LOSS:
      *label = s_settings.miles ? "LOSS FT" : "LOSS M";
      format_int(value, n, s_settings.miles ? s.loss_m * 3281 / 1000 : s.loss_m, true);
      return;
    case STAT_REMAIN:
      *label = s_settings.miles ? "TO GO MI" : "TO GO KM";
      if (s.has_course) {
        format_distance(value, n, s.remain_m);
      } else {
        snprintf(value, n, "--");
      }
      return;
    case STAT_ETA: {
      *label = "ETA";
      if (!s.has_course || s.dist_m <= 0) {
        snprintf(value, n, "--");
        return;
      }
      time_t eta = time(NULL) + (time_t)((int64_t)s.remain_m * s.elapsed_s / s.dist_m);
      strftime(value, n, clock_is_24h_style() ? "%H:%M" : "%l:%M", localtime(&eta));
      return;
    }
    case STAT_LAP_TIME:
      *label = "LAP";
      format_duration(value, n, s.elapsed_s - s.lap_start_s);
      return;
    case STAT_LAP_PACE: {
      *label = s_settings.miles ? "LAP /MI" : "LAP /KM";
      int32_t lap_m = s.dist_m - s.lap_start_m;
      format_pace(value, n, lap_m > 0 ? (s.elapsed_s - s.lap_start_s) * 1000 / lap_m : 0);
      return;
    }
    case STAT_CADENCE:
      *label = "CADENCE";
      format_int(value, n, cadence_spm(), true);
      return;
    case STAT_CLOCK: {
      *label = "CLOCK";
      time_t now = time(NULL);
      strftime(value, n, clock_is_24h_style() ? "%H:%M" : "%l:%M", localtime(&now));
      return;
    }
    case STAT_BATTERY:
      *label = "BATTERY";
      snprintf(value, n, "%d%%", battery_state_service_peek().charge_percent);
      return;
    case STAT_COUNT:
      return;
  }
}

// [label_w] narrows the label alone (from the aligned edge), leaving room for the map's top centre field.
static void draw_stat(GContext *ctx, Stat stat, GRect box, GTextAlignment align, int label_w) {
  const char *label = "";
  char value[16];
  format_stat(stat, &label, value, sizeof(value));
  graphics_context_set_text_color(ctx, GColorBlack);
  int label_x = align == GTextAlignmentRight ? box.origin.x + box.size.w - label_w : box.origin.x;
  graphics_draw_text(ctx, label, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(label_x, box.origin.y + 4, label_w, 16),
                     GTextOverflowModeTrailingEllipsis, align, NULL);
  graphics_draw_text(ctx, value, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD),
                     GRect(box.origin.x, box.origin.y + 20, box.size.w, 34),
                     GTextOverflowModeTrailingEllipsis, align, NULL);
}

// During a run the header only shows with the screen dots, or for an alert.
static bool paused(void) {
  return s.started && !s.running;
}

static bool header_shown(void) {
  return !s.started || s_indicator || s.scroll_x || s.scroll_y || s.error[0] || s.off_course;
}

static void draw_header(GContext *ctx, GRect bounds) {
  if (!header_shown()) return;
  bool scrolled = s.scroll_x || s.scroll_y;
  GRect bar = GRect(0, 0, bounds.size.w, HEADER_H);
  bool gps_ok = s.last_fix && time(NULL) - s.last_fix <= GPS_STALE_S;
  bool workout = s_settings.mode == MODE_WORKOUT;
  const char *state = !s.started ? MODE_NAMES[s_settings.mode] : !s.running ? "PAUSED"
                    : workout ? "WORKOUT" : hiking() ? "HIKING" : "RUNNING";
  if (s.error[0]) {
    graphics_context_set_fill_color(ctx, GColorRed);
    state = s.error;
  } else if (scrolled) {
    graphics_context_set_fill_color(ctx, GColorCobaltBlue);
    state = "MAP SCROLLED";
  } else if (s.off_course) {
    graphics_context_set_fill_color(ctx, GColorOrange);
    state = "OFF COURSE";
  } else {
    graphics_context_set_fill_color(ctx, s.running ? GColorIslamicGreen : GColorBlack);
  }
  graphics_fill_rect(ctx, bar, 0, GCornerNone);
  graphics_context_set_text_color(ctx, GColorWhite);
  GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  graphics_draw_text(ctx, state, font, GRect(4, 0, bounds.size.w - 60, HEADER_H),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  if (workout) return;  // no GPS in a workout
  graphics_draw_text(ctx, gps_ok ? "GPS" : "NO GPS", font,
                     GRect(bounds.size.w - 64, 0, 60, HEADER_H),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentRight, NULL);
}

static void draw_box(GContext *ctx, Stat stat, GRect box, GTextAlignment align, int label_w) {
  if (stat == STAT_NONE) return;
  draw_stat(ctx, stat, box, align, label_w);
}

// A screen counts when at least one of its fields is set.
static bool screen_used(int screen) {
  bool stats = s_settings.mode == MODE_RUN;
  const uint8_t *slots = stats ? s_settings.stats_slots[screen] : map_screens()->map_slots[screen];
  for (int i = 0; i < (stats ? STAT_SLOTS : MAP_SLOTS); i++) {
    if (slots[i] != STAT_NONE) return true;
  }
  return false;
}

static bool showing_map(void) {
  return map_mode() && s.map_page;
}

// One dot per used screen down the right edge; the current one is filled.
static void draw_screen_dots(GContext *ctx, GRect bounds) {
  int used = 0;
  for (int i = 0; i < MAP_SCREENS; i++) used += screen_used(i);
  if (used < 2) return;
  int x = bounds.size.w - 8;
  int y = bounds.size.h / 2 - (used - 1) * 8;
  for (int i = 0; i < MAP_SCREENS; i++) {
    if (!screen_used(i)) continue;
    GPoint c = GPoint(x, y);
    graphics_context_set_fill_color(ctx, GColorWhite);
    graphics_fill_circle(ctx, c, 6);
    graphics_context_set_fill_color(ctx, i == s.screen ? GColorBlack : GColorWhite);
    graphics_fill_circle(ctx, c, 4);
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_draw_circle(ctx, c, 4);
    y += 16;
  }
}

static void draw_map(GContext *ctx, GRect bounds) {
  GPoint off = GPoint(s.shift.x + s.drag.x, s.shift.y + s.drag.y);
  if (s_has_image) {
    if (off.x || off.y) {
      graphics_context_set_fill_color(ctx, GColorLightGray);
      graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    }
    graphics_draw_bitmap_in_rect(ctx, s_front, GRect(off.x, off.y, MAP_W, MAP_H));
  } else {
    graphics_context_set_fill_color(ctx, GColorLightGray);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_text_color(ctx, GColorBlack);
    graphics_draw_text(ctx, "Waiting for map", fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                       GRect(0, bounds.size.h / 2 - 12, bounds.size.w, 24),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  }
  if (s.has_marker) {
    // The phone places the marker on the image it's sending, which already includes [shift].
    GPoint m = GPoint(s.marker.x + s.drag.x, s.marker.y + s.drag.y);
    graphics_context_set_fill_color(ctx, GColorRed);
    if (s.marker_dir >= 0) {
      gpath_rotate_to(s_arrow, DEG_TO_TRIGANGLE(s.marker_dir));
      gpath_move_to(s_arrow, m);
      gpath_draw_filled(ctx, s_arrow);
    }
    graphics_fill_circle(ctx, m, 6);
    graphics_context_set_stroke_color(ctx, GColorWhite);
    graphics_context_set_stroke_width(ctx, 2);
    graphics_draw_circle(ctx, m, 6);
    graphics_context_set_stroke_width(ctx, 1);
  }
  int w = bounds.size.w;
  int h = bounds.size.h;
  const uint8_t *slots = map_screens()->map_slots[s.screen];
  if (s_settings.overlay == OVERLAY_BAR) {
    draw_box(ctx, slots[0], GRect(0, h - BOX_H, w / 2, BOX_H), GTextAlignmentCenter, w / 2);
    draw_box(ctx, slots[1], GRect(w / 2, h - BOX_H, w - w / 2, BOX_H), GTextAlignmentCenter, w - w / 2);
  } else if (s_settings.overlay == OVERLAY_CORNERS) {
    int top = header_shown() ? HEADER_H : 0;  // move up into the hidden header's space
    int half = w / 2 - CORNER_MARGIN;
    // The top centre field sits between the top corners' labels, above their values,
    // as a bare value like a status bar clock; the labels give it that room.
    Stat centre = slots[4];
    int label_w = centre == STAT_NONE ? half : w / 2 - CENTRE_W / 2 - CORNER_MARGIN;
    draw_box(ctx, slots[0], GRect(CORNER_MARGIN, top, half, BOX_H), GTextAlignmentLeft, label_w);
    draw_box(ctx, slots[1], GRect(w / 2, top, half, BOX_H), GTextAlignmentRight, label_w);
    draw_box(ctx, slots[2], GRect(CORNER_MARGIN, h - BOX_H, half, BOX_H), GTextAlignmentLeft, half);
    draw_box(ctx, slots[3], GRect(w / 2, h - BOX_H, half, BOX_H), GTextAlignmentRight, half);
    if (centre != STAT_NONE) {
      const char *label = "";
      char value[16];
      format_stat(centre, &label, value, sizeof(value));
      graphics_context_set_text_color(ctx, GColorBlack);
      graphics_draw_text(ctx, value, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                         GRect(w / 2 - CENTRE_W / 2, top, CENTRE_W, 22),
                         GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
    }
  }
  draw_header(ctx, bounds);
  if (s_indicator) draw_screen_dots(ctx, bounds);
}

static void draw_stats_page(GContext *ctx, GRect bounds, const uint8_t *slots) {
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  draw_header(ctx, bounds);
  int rows = STAT_SLOTS / 2;
  int w = bounds.size.w / 2;
  int h = (bounds.size.h - HEADER_H) / rows;
  graphics_context_set_stroke_color(ctx, GColorLightGray);
  for (int i = 0; i < STAT_SLOTS; i++) {
    GRect box = GRect(i % 2 * w, HEADER_H + i / 2 * h, w, h);
    draw_stat(ctx, slots[i], box, GTextAlignmentCenter, box.size.w);
  }
  for (int r = 1; r < rows; r++) {
    graphics_draw_line(ctx, GPoint(0, HEADER_H + r * h), GPoint(bounds.size.w, HEADER_H + r * h));
  }
  graphics_draw_line(ctx, GPoint(w, HEADER_H), GPoint(w, bounds.size.h));
}

static void draw_heart(GContext *ctx, GPoint c) {
  graphics_fill_circle(ctx, GPoint(c.x - 5, c.y - 3), 6);
  graphics_fill_circle(ctx, GPoint(c.x + 5, c.y - 3), 6);
  gpath_move_to(s_heart, c);
  gpath_draw_filled(ctx, s_heart);
}

// Workout: heart rate on a zone arc, total time inside it, average and max below.
static void draw_workout(GContext *ctx, GRect bounds) {
  static const GColor8 ZONE_COLORS[] = {
    {GColorVividCeruleanARGB8}, {GColorIslamicGreenARGB8}, {GColorChromeYellowARGB8},
    {GColorOrangeARGB8}, {GColorRedARGB8},
  };
  int w = bounds.size.w;
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  if (header_shown()) {
    draw_header(ctx, bounds);
  } else {
    char clock[8];
    time_t now = time(NULL);
    strftime(clock, sizeof(clock), clock_is_24h_style() ? "%H:%M" : "%l:%M", localtime(&now));
    graphics_context_set_text_color(ctx, GColorBlack);
    graphics_draw_text(ctx, clock, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(0, 0, w, HEADER_H),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
    if (s.running) {
      graphics_context_set_fill_color(ctx, GColorRed);
      graphics_fill_circle(ctx, GPoint(w / 2 - 34, HEADER_H / 2 + 1), 4);
    }
  }

  // The arc spans 50-100% of max HR over 270 degrees, open at the bottom; 10% per zone.
  GPoint c = GPoint(w / 2, 112);
  int radius = 80;
  GRect ring = GRect(c.x - radius, c.y - radius, radius * 2, radius * 2);
  int32_t start = DEG_TO_TRIGANGLE(-135);
  int32_t span = DEG_TO_TRIGANGLE(270) / 5;
  graphics_context_set_stroke_width(ctx, 8);
  for (int z = 0; z < 5; z++) {
    graphics_context_set_stroke_color(ctx, ZONE_COLORS[z]);
    graphics_draw_arc(ctx, ring, GOvalScaleModeFitCircle, start + z * span + DEG_TO_TRIGANGLE(2),
                      start + (z + 1) * span - DEG_TO_TRIGANGLE(2));
  }
  graphics_context_set_stroke_width(ctx, 1);
  bool has_hr = s.hr > 0;
  int zone = hr_zone();
  if (has_hr) {
    int pct = s.hr * 100 / s_settings.max_hr;
    pct = pct < 50 ? 50 : pct > 100 ? 100 : pct;
    int32_t angle = start + DEG_TO_TRIGANGLE(270) * (pct - 50) / 50;
    GPoint dot = GPoint(c.x + sin_lookup(angle) * radius / TRIG_MAX_RATIO,
                        c.y - cos_lookup(angle) * radius / TRIG_MAX_RATIO);
    graphics_context_set_fill_color(ctx, GColorWhite);
    graphics_fill_circle(ctx, dot, 8);
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 2);
    graphics_draw_circle(ctx, dot, 8);
    graphics_context_set_stroke_width(ctx, 1);
  }

  char text[16];
  if (has_hr) {
    snprintf(text, sizeof(text), "ZONE %d", zone);
    graphics_context_set_text_color(ctx, ZONE_COLORS[zone - 1]);
    graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(0, 56, w, 22),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  }
  graphics_context_set_fill_color(ctx, GColorRed);
  draw_heart(ctx, GPoint(c.x - 48, 100));
  graphics_context_set_text_color(ctx, GColorBlack);
  format_int(text, sizeof(text), s.hr, has_hr);
  graphics_draw_text(ctx, text, fonts_get_system_font(has_hr ? FONT_KEY_LECO_42_NUMBERS : FONT_KEY_GOTHIC_28_BOLD),
                     GRect(c.x - 32, has_hr ? 76 : 84, 76, 48),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  graphics_draw_text(ctx, "HEART RATE", fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD), GRect(0, 124, w, 16),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  format_duration(text, sizeof(text), s.elapsed_s);
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD), GRect(0, 138, w, 34),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);

  int y = bounds.size.h - 46;
  graphics_context_set_stroke_color(ctx, GColorLightGray);
  graphics_draw_line(ctx, GPoint(w / 2, y + 4), GPoint(w / 2, bounds.size.h - 4));
  graphics_context_set_text_color(ctx, GColorRed);
  GFont small = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
  graphics_draw_text(ctx, "AVG BPM", small, GRect(0, y, w / 2, 16), GTextOverflowModeTrailingEllipsis,
                     GTextAlignmentCenter, NULL);
  graphics_draw_text(ctx, "MAX BPM", small, GRect(w / 2, y, w / 2, 16), GTextOverflowModeTrailingEllipsis,
                     GTextAlignmentCenter, NULL);
  graphics_context_set_text_color(ctx, GColorBlack);
  GFont big = fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD);
  format_int(text, sizeof(text), s.hr_count ? (int)(s.hr_sum / s.hr_count) : 0, s.hr_count > 0);
  graphics_draw_text(ctx, text, big, GRect(0, y + 12, w / 2, 34), GTextOverflowModeTrailingEllipsis,
                     GTextAlignmentCenter, NULL);
  format_int(text, sizeof(text), s.hr_max, s.hr_max > 0);
  graphics_draw_text(ctx, text, big, GRect(w / 2, y + 12, w / 2, 34), GTextOverflowModeTrailingEllipsis,
                     GTextAlignmentCenter, NULL);
}

// Room is left below the text for a QR code to the repo.
static void draw_install_notice(GContext *ctx, GRect bounds) {
  GRect box = GRect(10, HEADER_H + 10, bounds.size.w - 20, bounds.size.h - HEADER_H - 20);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, box, 6, GCornersAll);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_draw_round_rect(ctx, box, 6);
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, "Phone app needed", fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
                     GRect(box.origin.x + 6, box.origin.y + 8, box.size.w - 12, 30),
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  graphics_draw_text(ctx, "Install Get After It on your phone.", fonts_get_system_font(FONT_KEY_GOTHIC_18),
                     GRect(box.origin.x + 6, box.origin.y + 40, box.size.w - 12, 44),
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

// Shown beside the Select button until the run starts: hold starts.
static void draw_start_hint(GContext *ctx, GRect bounds) {
  GRect tab = GRect(bounds.size.w - 70, bounds.size.h / 2 - 16, 70, 32);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, tab, 6, GCornersLeft);
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, "HOLD", fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(tab.origin.x + 6, tab.origin.y + 4, 44, 22),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  graphics_context_set_fill_color(ctx, GColorWhite);
  gpath_move_to(s_play, GPoint(bounds.size.w - 14, bounds.size.h / 2));
  gpath_draw_filled(ctx, s_play);
}

// Full-screen while paused; the icons sit beside the Up (stop) and Down (resume) buttons.
static void draw_paused(GContext *ctx, GRect bounds) {
  int w = bounds.size.w - ACTION_BAR_W;
  int up_y = bounds.size.h / 4;
  int down_y = bounds.size.h * 3 / 4;
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, GRect(w, 0, ACTION_BAR_W, bounds.size.h), 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, GRect(w + 8, up_y - 7, 14, 14), 0, GCornerNone);
  gpath_move_to(s_play, GPoint(w + ACTION_BAR_W / 2, down_y));
  gpath_draw_filled(ctx, s_play);
  graphics_context_set_text_color(ctx, GColorBlack);
  GFont label = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  graphics_draw_text(ctx, "Hold: stop & save", label, GRect(0, up_y - 12, w - 6, 22),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentRight, NULL);
  graphics_draw_text(ctx, "Paused", fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD),
                     GRect(0, bounds.size.h / 2 - 30, w, 34),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  char elapsed[12];
  format_duration(elapsed, sizeof(elapsed), s.elapsed_s);
  graphics_draw_text(ctx, elapsed, fonts_get_system_font(FONT_KEY_GOTHIC_24),
                     GRect(0, bounds.size.h / 2 + 2, w, 28),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  graphics_draw_text(ctx, "Resume", label, GRect(0, down_y - 12, w - 6, 22),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentRight, NULL);
}

static const Stat RUN_SUMMARY[] = {
  STAT_DIST, STAT_ELAPSED, STAT_MOVING, STAT_AVG_PACE, STAT_AVG_HR, STAT_MAX_HR, STAT_GAIN, STAT_LOSS,
};
static const Stat WORKOUT_SUMMARY[] = {STAT_ELAPSED, STAT_AVG_HR, STAT_MAX_HR};

static const Stat *summary_stats(int *count) {
  bool workout = s_settings.mode == MODE_WORKOUT;
  *count = workout ? (int)ARRAY_LENGTH(WORKOUT_SUMMARY) : (int)ARRAY_LENGTH(RUN_SUMMARY);
  return workout ? WORKOUT_SUMMARY : RUN_SUMMARY;
}

static int summary_visible_rows(void) {
  return (layer_get_bounds(s_layer).size.h - HEADER_H) / SUMMARY_ROW_H;
}

static void draw_summary(GContext *ctx, GRect bounds) {
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, GRect(0, 0, bounds.size.w, HEADER_H), 0, GCornerNone);
  graphics_context_set_text_color(ctx, GColorWhite);
  int count;
  const Stat *stats = summary_stats(&count);
  graphics_draw_text(ctx, s_settings.mode == MODE_WORKOUT ? "WORKOUT SUMMARY"
                     : hiking() ? "HIKE SUMMARY" : "RUN SUMMARY",
                     fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(4, 0, bounds.size.w - 8, HEADER_H),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_context_set_stroke_color(ctx, GColorLightGray);
  int rows = summary_visible_rows();
  for (int r = 0; r < rows && s.summary_top + r < count; r++) {
    const char *label = "";
    char value[16];
    format_stat(stats[s.summary_top + r], &label, value, sizeof(value));
    int y = HEADER_H + r * SUMMARY_ROW_H;
    graphics_draw_text(ctx, label, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                       GRect(6, y + 8, bounds.size.w / 2, 22),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    graphics_draw_text(ctx, value, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD),
                       GRect(bounds.size.w / 2, y, bounds.size.w / 2 - 6, 34),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentRight, NULL);
    graphics_draw_line(ctx, GPoint(0, y + SUMMARY_ROW_H - 1), GPoint(bounds.size.w, y + SUMMARY_ROW_H - 1));
  }
}

static uint64_t now_ms(void) {
  time_t secs;
  uint16_t ms;
  time_ms(&secs, &ms);
  return (uint64_t)secs * 1000 + ms;
}

// Fills beside the held button; full means the hold has done its thing.
static void draw_hold_ring(GContext *ctx, GRect bounds) {
  int y = s_hold_button == BUTTON_ID_UP ? bounds.size.h / 4
        : s_hold_button == BUTTON_ID_DOWN ? bounds.size.h * 3 / 4
        : bounds.size.h / 2;
  GPoint c = GPoint(bounds.size.w - 18, y);
  uint64_t held = now_ms() - s_hold_start_ms;
  int32_t angle = held >= s_hold_ms ? TRIG_MAX_ANGLE : (int32_t)(TRIG_MAX_ANGLE * held / s_hold_ms);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_circle(ctx, c, 14);
  GRect ring = GRect(c.x - 10, c.y - 10, 21, 21);
  graphics_context_set_stroke_width(ctx, 4);
  graphics_context_set_stroke_color(ctx, GColorLightGray);
  graphics_draw_arc(ctx, ring, GOvalScaleModeFitCircle, 0, TRIG_MAX_ANGLE);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_draw_arc(ctx, ring, GOvalScaleModeFitCircle, 0, angle);
  graphics_context_set_stroke_width(ctx, 1);
}

// The first screen: the activities enabled in the phone's settings, one per row.
static void draw_picker(GContext *ctx, GRect bounds) {
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, GRect(0, 0, bounds.size.w, HEADER_H), 0, GCornerNone);
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, "CHOOSE ACTIVITY", fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(4, 0, bounds.size.w - 8, HEADER_H),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  int row_h = (bounds.size.h - HEADER_H) / MODE_COUNT;
  int y = HEADER_H;
  for (int m = 0; m < MODE_COUNT; m++) {
    if (!(s_settings.modes & (1 << m))) continue;
    bool picked = m == s.pick;
    if (picked) {
      graphics_context_set_fill_color(ctx, GColorIslamicGreen);
      graphics_fill_rect(ctx, GRect(0, y, bounds.size.w, row_h), 0, GCornerNone);
    }
    graphics_context_set_text_color(ctx, picked ? GColorWhite : GColorBlack);
    graphics_draw_text(ctx, MODE_NAMES[m], fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
                       GRect(10, y + (row_h - 30) / 2, bounds.size.w - 20, 30),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    y += row_h;
  }
}

static void layer_update(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  if (s.picking) {
    draw_picker(ctx, bounds);
    if (s_companion_missing) draw_install_notice(ctx, bounds);
    return;
  }
  if (s.summary) {
    draw_summary(ctx, bounds);
    return;
  }
  if (paused()) {
    draw_paused(ctx, bounds);
    if (s_hold_button != NUM_BUTTONS && now_ms() - s_hold_start_ms >= HOLD_SHOW_MS) draw_hold_ring(ctx, bounds);
    return;
  }
  if (showing_map()) {
    draw_map(ctx, bounds);
  } else if (s_settings.mode == MODE_RUN) {
    draw_stats_page(ctx, bounds, s_settings.stats_slots[s.screen]);
    if (s_indicator) draw_screen_dots(ctx, bounds);
  } else if (s_settings.mode == MODE_WORKOUT) {
    draw_workout(ctx, bounds);
  } else {
    draw_stats_page(ctx, bounds, map_screens()->stat_slots);
  }
  if (!s.started) draw_start_hint(ctx, bounds);
  if (s_companion_missing) draw_install_notice(ctx, bounds);
  if (s_hold_button != NUM_BUTTONS && now_ms() - s_hold_start_ms >= HOLD_SHOW_MS) draw_hold_ring(ctx, bounds);
}

static void send_state(uint32_t cmd);

static void tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  time_t quiet = time(NULL) - s_companion_at;
  if (quiet >= COMPANION_PING_S && quiet % COMPANION_PING_S == 0) send_state(0);
  s.steps[s.steps_i] = (int)health_service_sum_today(HealthMetricStepCount);
  s.steps_i = (s.steps_i + 1) % CADENCE_WINDOW_S;
  // A recent chest-strap reading wins over the wrist sensor, which then drops
  // back to its normal background rate to save battery.
  bool strap = time(NULL) - s.strap_hr_at <= STRAP_HR_FRESH_S;
  if (strap != s_strap_hr) {
    s_strap_hr = strap;
    health_service_set_heart_rate_sample_period(strap ? 0 : 1);
  }
  s.hr = strap ? s.strap_hr : (int)health_service_peek_current_value(HealthMetricHeartRateBPM);
  if (s.running) {
    s.elapsed_s++;
    if (s.hr > s.hr_max) s.hr_max = s.hr;
    if (s.hr > 0) {
      s.hr_sum += s.hr;
      s.hr_count++;
    }
    if (s.dist_m - s.lap_start_m >= unit_m()) {
      s.lap_start_s = s.elapsed_s;
      s.lap_start_m += unit_m();
      vibes_short_pulse();
    }
  }
  layer_mark_dirty(s_layer);
}

// cmd is CMD_START_KEY, CMD_STOP_KEY, HELLO_KEY or 0 for none.
static void send_state(uint32_t cmd) {
  DictionaryIterator *out;
  AppMessageResult result = app_message_outbox_begin(&out);
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "outbox_begin failed: %d", (int)result);
    return;
  }
  dict_write_int32(out, ZOOM_KEY, s.zoom);
  dict_write_int32(out, SCROLL_X_KEY, s.scroll_x);
  dict_write_int32(out, SCROLL_Y_KEY, s.scroll_y);
  if (cmd) dict_write_uint8(out, cmd, 1);
  if (s.started && s_settings.mode != MODE_WORKOUT) dict_write_uint8(out, PAUSED_KEY, !s.running);
  app_message_outbox_send();
}

static void hide_indicator(void *context) {
  s_indicator = false;
  s_indicator_timer = NULL;
  layer_mark_dirty(s_layer);
}

static void show_indicator(void) {
  s_indicator = true;
  if (s_indicator_timer) {
    app_timer_reschedule(s_indicator_timer, INDICATOR_MS);
  } else {
    s_indicator_timer = app_timer_register(INDICATOR_MS, hide_indicator, NULL);
  }
  layer_mark_dirty(s_layer);
}

// Workouts stay on the watch; runs are recorded by the companion.
static void start_run(void) {
  s.started = true;
  s.running = true;
  show_indicator();
  vibes_short_pulse();
  if (s_settings.mode != MODE_WORKOUT) send_state(CMD_START_KEY);
  layer_mark_dirty(s_layer);
}

// Ends the run on the watch; the caller decides whether the companion needs telling.
static void end_run(void) {
  s.started = false;
  s.running = false;
  s.summary = true;
  vibes_double_pulse();
  layer_mark_dirty(s_layer);
}

static void stop_run(void) {
  end_run();
  if (s_settings.mode != MODE_WORKOUT) send_state(CMD_STOP_KEY);
}

// Picks the highlighted activity on the first screen; the companion keeps the choice.
static void choose_activity(void) {
  s.picking = false;
  s_settings.mode = s.pick;
  persist_write_data(SETTINGS_KEY, &s_settings, sizeof(s_settings));
  s.screen = 0;
  s.map_page = true;
  DictionaryIterator *out;
  AppMessageResult result = app_message_outbox_begin(&out);
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "outbox_begin failed: %d", (int)result);
    return;
  }
  dict_write_uint8(out, MODE_KEY, s_settings.mode);
  app_message_outbox_send();
}

// Moves the first screen's highlight to the next enabled activity, wrapping round.
static void move_pick(int step) {
  for (int i = 1; i < MODE_COUNT; i++) {
    int m = (s.pick + step * i + MODE_COUNT) % MODE_COUNT;
    if (s_settings.modes & (1 << m)) {
      s.pick = m;
      return;
    }
  }
}

// Pauses or resumes; the companion stops recording while paused.
static void set_running(bool running) {
  s.running = running;
  if (running) {
    show_indicator();
    vibes_short_pulse();
  } else {
    vibes_double_pulse();
  }
  send_state(0);
  layer_mark_dirty(s_layer);
}

static void select_long_handler(ClickRecognizerRef recognizer, void *context) {
  if (s.picking || paused() || s.summary) return;
  if (!s.started) {
    start_run();
    return;
  }
  set_running(false);
}

static void select_handler(ClickRecognizerRef recognizer, void *context) {
  if (paused() || s.summary) return;
  if (s.picking) {
    choose_activity();
  } else if (map_mode()) {
    s.map_page = !s.map_page;
  }
  layer_mark_dirty(s_layer);
}

// Back goes from the ready screen to the activity list and from there exits;
// during a run it does nothing, so dismissing notifications can't end or resume one.
static void back_handler(ClickRecognizerRef recognizer, void *context) {
  // The summary's totals aren't reset for another activity, so it quits too.
  if (s.picking || s.summary) {
    window_stack_pop(true);
    return;
  }
  if (paused()) return;
  if (s.scroll_x || s.scroll_y) {
    // Back to the runner's position.
    s.scroll_x = 0;
    s.scroll_y = 0;
    send_state(0);
    layer_mark_dirty(s_layer);
  } else if (!s.started) {
    s.picking = true;
    s.pick = s_settings.mode;
    layer_mark_dirty(s_layer);
  }
}

static void zoom_handler(ClickRecognizerRef recognizer, void *context) {
  if (s.picking || s.summary || paused() || !showing_map()) return;
  int zoom = s.zoom + (click_recognizer_get_button_id(recognizer) == BUTTON_ID_UP ? 1 : -1);
  if (zoom < ZOOM_MIN || zoom > ZOOM_MAX) return;
  // Keep the scrolled-to spot: each zoom level doubles pixel distances.
  if (zoom > s.zoom) {
    s.scroll_x *= 2;
    s.scroll_y *= 2;
  } else {
    s.scroll_x /= 2;
    s.scroll_y /= 2;
  }
  s.zoom = zoom;
  send_state(0);
}

// Steps to the next screen that has at least one field, so unused screens are skipped.
static void screen_handler(ClickRecognizerRef recognizer, void *context) {
  bool up = click_recognizer_get_button_id(recognizer) == BUTTON_ID_UP;
  if (s.picking) {
    move_pick(up ? -1 : 1);
    layer_mark_dirty(s_layer);
    return;
  }
  if (s.summary) {
    int count;
    summary_stats(&count);
    int top = s.summary_top + (up ? -1 : 1);
    if (top < 0 || top > count - summary_visible_rows()) return;
    s.summary_top = top;
    layer_mark_dirty(s_layer);
    return;
  }
  if (paused()) {
    // Up stops only when held (see hold_down), so a bump can't end the run.
    if (!up) set_running(true);
    return;
  }
  if (!showing_map() && s_settings.mode != MODE_RUN) return;
  int step = up ? MAP_SCREENS - 1 : 1;
  int screen = s.screen;
  for (int tries = 0; tries < MAP_SCREENS; tries++) {
    screen = (screen + step) % MAP_SCREENS;
    if (screen_used(screen)) {
      s.screen = screen;
      show_indicator();
      return;
    }
  }
}

static void hold_tick(void *context) {
  bool done = now_ms() - s_hold_start_ms >= s_hold_ms;
  s_hold_timer = done ? NULL : app_timer_register(40, hold_tick, NULL);
  if (done && paused() && s_hold_button == BUTTON_ID_UP) {
    s_hold_button = NUM_BUTTONS;
    stop_run();
  }
  layer_mark_dirty(s_layer);
}

// Raw press/release run alongside the click handlers and drive the ring; the
// paused screen's long Up hold (stop and save) is timed here too.
static void hold_down(ClickRecognizerRef recognizer, void *context) {
  // Up/Down holds only zoom the map, and the only hold while paused is stop and save.
  ButtonId button = click_recognizer_get_button_id(recognizer);
  bool stop_hold = paused() && button == BUTTON_ID_UP;
  if (!stop_hold && (s.picking || s.summary || paused() || (button != BUTTON_ID_SELECT && !showing_map()))) return;
  s_hold_ms = stop_hold ? STOP_HOLD_MS : HOLD_MS;
  s_hold_button = button;
  s_hold_start_ms = now_ms();
  if (!s_hold_timer) s_hold_timer = app_timer_register(40, hold_tick, NULL);
}

static void hold_up(ClickRecognizerRef recognizer, void *context) {
  s_hold_button = NUM_BUTTONS;
  if (s_hold_timer) {
    app_timer_cancel(s_hold_timer);
    s_hold_timer = NULL;
  }
  layer_mark_dirty(s_layer);
}

static void click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_handler);
  window_long_click_subscribe(BUTTON_ID_SELECT, HOLD_MS, select_long_handler, NULL);
  window_single_click_subscribe(BUTTON_ID_BACK, back_handler);
  window_single_click_subscribe(BUTTON_ID_UP, screen_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, screen_handler);
  window_long_click_subscribe(BUTTON_ID_UP, HOLD_MS, zoom_handler, NULL);
  window_long_click_subscribe(BUTTON_ID_DOWN, HOLD_MS, zoom_handler, NULL);
  window_raw_click_subscribe(BUTTON_ID_SELECT, hold_down, hold_up, NULL);
  window_raw_click_subscribe(BUTTON_ID_UP, hold_down, hold_up, NULL);
  window_raw_click_subscribe(BUTTON_ID_DOWN, hold_down, hold_up, NULL);
}

static void receive_chunk(int32_t offset, const uint8_t *data, uint16_t len) {
  if (offset < 0 || offset + len > MAP_W * MAP_H) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "image chunk out of range: %ld+%u", (long)offset, len);
    return;
  }
  uint8_t *pixels = gbitmap_get_data(s_back);
  uint16_t stride = gbitmap_get_bytes_per_row(s_back);
  for (uint16_t i = 0; i < len; i++) {
    int32_t p = offset + i;
    pixels[p / MAP_W * stride + p % MAP_W] = data[i];
  }
  if (offset + len == MAP_W * MAP_H) {
    GBitmap *shown = s_front;
    s_front = s_back;
    s_back = shown;
    s_has_image = true;
    s.shift = GPoint(0, 0);  // the new image is drawn around the scrolled centre
  }
}

// Copies [count] Stat values from a settings byte array; false if it doesn't hold exactly that many valid ones.
static bool read_slots(DictionaryIterator *iter, uint32_t key, uint8_t *into, int count) {
  Tuple *t = dict_find(iter, key);
  if (t->length != count) return false;
  const uint8_t *data = t->value->data;
  for (int i = 0; i < count; i++) {
    if (data[i] >= STAT_COUNT) return false;
    into[i] = data[i];
  }
  return true;
}

static void apply_settings(DictionaryIterator *iter) {
  int32_t mode = dict_find(iter, SET_MODE_KEY)->value->int32;
  int32_t modes = dict_find(iter, SET_MODES_KEY)->value->int32;
  int32_t max_hr = dict_find(iter, SET_MAX_HR_KEY)->value->int32;
  int32_t overlay = dict_find(iter, SET_OVERLAY_KEY)->value->int32;
  Settings next = {
    .miles = dict_find(iter, SET_MILES_KEY)->value->int32,
    .backlight = dict_find(iter, SET_BACKLIGHT_KEY)->value->int32,
    .max_hr = max_hr,
    .overlay = overlay,
    // The activity can't change under one in progress.
    .mode = s.started ? s_settings.mode : mode,
    .modes = modes,
  };
  bool ok = mode >= 0 && mode < MODE_COUNT && modes > 0 && modes < (1 << MODE_COUNT) && (modes & (1 << mode)) &&
            overlay >= 0 && overlay <= OVERLAY_CORNERS && max_hr >= 100 && max_hr <= 250 &&
            read_slots(iter, SET_MAP_SLOTS_KEY, (uint8_t *)next.maps, sizeof(next.maps)) &&
            read_slots(iter, SET_STATS_SLOTS_KEY, &next.stats_slots[0][0], STATS_SCREENS * STAT_SLOTS);
  if (!ok) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "invalid settings message");
    strncpy(s.error, "Invalid settings", sizeof(s.error) - 1);
    return;
  }
  s_settings = next;
  // Kept so the layout and units are right before the phone sends them again.
  persist_write_data(SETTINGS_KEY, &s_settings, sizeof(s_settings));
  light_enable(s_settings.backlight);
  s.screen = 0;
  if (!s.started) s.map_page = true;
  if (s.picking) s.pick = s_settings.mode;
}

static void companion_check(void *context) {
  s_companion_missing = !s_companion_seen;
  layer_mark_dirty(s_layer);
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  // Only the companion sends to the watch.
  s_companion_seen = true;
  s_companion_missing = false;
  s_companion_at = time(NULL);
  // The phone has reset its track, so later stats would overwrite the stopped run's.
  if (s.summary) return;
  Tuple *t;
  if (dict_find(iter, SET_MILES_KEY)) apply_settings(iter);
  // A workout is the watch's own; the companion's run state doesn't apply.
  bool workout = s_settings.mode == MODE_WORKOUT;
  // Run started or stopped from the phone app.
  if (!workout && dict_find(iter, CO_RUN_STARTED) && !s.started) {
    s.picking = false;
    s.started = true;
    s.running = true;
    show_indicator();
    vibes_short_pulse();
  }
  if (!workout && dict_find(iter, CO_RUN_STOPPED) && s.started) {
    end_run();
    return;
  }
  // Run totals from the companion. Pace is absent while stopped.
  if ((t = dict_find(iter, CO_DIST))) {
    s.dist_m = t->value->uint32;
    Tuple *pace = dict_find(iter, CO_PACE);
    s.pace_s_km = pace ? pace->value->uint16 : 0;
    // Shown rounded to 5 s, and only moved once the pace is 5 s away from what's
    // shown, so a pace sitting on a rounding boundary doesn't flicker.
    int32_t per_unit = s.pace_s_km * unit_m() / 1000;
    if (s.pace_s_km <= 0 || per_unit >= 3600) {
      s.pace_shown = 0;
    } else if (!s.pace_shown || (per_unit > s.pace_shown ? per_unit - s.pace_shown : s.pace_shown - per_unit) >= 5) {
      s.pace_shown = (per_unit + 2) / 5 * 5;
    }
    s.gain_m = dict_find(iter, CO_GAIN)->value->uint32;
    s.loss_m = dict_find(iter, CO_LOSS)->value->uint32;
  }
  if ((t = dict_find(iter, CO_MOVING))) s.moving_s = t->value->uint32;
  if ((t = dict_find(iter, CO_HR))) {
    s.strap_hr = t->value->uint16;
    s.strap_hr_at = time(NULL);
  }
  if (dict_find(iter, CO_FIX)) {
    Tuple *elev = dict_find(iter, CO_ELEV);
    s.has_elev = elev != NULL;
    if (elev) s.elev_m = elev->value->int32;
    s.last_fix = time(NULL);
    s.error[0] = '\0';
  }
  if ((t = dict_find(iter, CO_REMAIN))) {
    s.has_course = t->value->int32 >= 0;
    s.remain_m = t->value->int32;
    bool off = dict_find(iter, CO_OFF_COURSE)->value->uint8;
    if (off && !s.off_course && s.running) vibes_long_pulse();
    s.off_course = off;
  }
  if ((t = dict_find(iter, CO_IMG_DATA))) {
    receive_chunk(dict_find(iter, CO_IMG_OFFSET)->value->int32, t->value->data, t->length);
  }
  if ((t = dict_find(iter, CO_MARKER_X))) {
    s.has_marker = true;
    s.marker = GPoint(t->value->int32, dict_find(iter, CO_MARKER_Y)->value->int32);
    s.marker_dir = dict_find(iter, CO_MARKER_DIR)->value->int32;
  }
  if ((t = dict_find(iter, CO_ERROR))) {
    strncpy(s.error, t->value->cstring, sizeof(s.error) - 1);
  }
  layer_mark_dirty(s_layer);
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "inbox dropped: %d", (int)reason);
}

// Dragging the map moves the shown image under the finger; on lift the phone
// redraws around the new centre.
static void touch_handler(const TouchEvent *event, void *context) {
  if (s.picking || !showing_map() || s.summary || paused()) return;
  static GPoint start;
  switch (event->type) {
    case TouchEvent_Touchdown:
      start = GPoint(event->x, event->y);
      s.dragging = false;
      return;
    case TouchEvent_PositionUpdate: {
      int dx = event->x - start.x;
      int dy = event->y - start.y;
      if (!s.dragging && (dx < 0 ? -dx : dx) + (dy < 0 ? -dy : dy) < DRAG_MIN_PX) return;
      s.dragging = true;
      s.drag = GPoint(dx, dy);
      layer_mark_dirty(s_layer);
      return;
    }
    case TouchEvent_Liftoff:
      if (!s.dragging) return;
      // Dragging the map right moves the view's centre left.
      s.scroll_x -= s.drag.x;
      s.scroll_y -= s.drag.y;
      s.shift = GPoint(s.shift.x + s.drag.x, s.shift.y + s.drag.y);
      s.drag = GPoint(0, 0);
      s.dragging = false;
      send_state(0);
      layer_mark_dirty(s_layer);
      return;
  }
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_layer = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_layer, layer_update);
  layer_add_child(root, s_layer);
  if (touch_service_is_enabled()) touch_service_subscribe(touch_handler, NULL);
}

static void window_unload(Window *window) {
  if (touch_service_is_enabled()) touch_service_unsubscribe();
  layer_destroy(s_layer);
}

static void load_run(void) {
  if (!persist_exists(RUN_KEY)) return;
  RunSave r;
  persist_read_data(RUN_KEY, &r, sizeof(r));
  s.started = true;
  s.running = r.running;
  s.elapsed_s = r.elapsed_s + (r.running ? time(NULL) - r.saved_at : 0);
  s.dist_m = r.dist_m;
  s.moving_s = r.moving_s;
  s.gain_m = r.gain_m;
  s.loss_m = r.loss_m;
  s.hr_max = r.hr_max;
  s.hr_sum = r.hr_sum;
  s.hr_count = r.hr_count;
  s.lap_start_s = r.lap_start_s;
  s.lap_start_m = r.lap_start_m;
}

static void save_run(void) {
  if (!s.started) {
    persist_delete(RUN_KEY);
    return;
  }
  RunSave r = {
    .running = s.running,
    .elapsed_s = s.elapsed_s,
    .dist_m = s.dist_m,
    .moving_s = s.moving_s,
    .gain_m = s.gain_m,
    .loss_m = s.loss_m,
    .hr_max = s.hr_max,
    .hr_sum = s.hr_sum,
    .hr_count = s.hr_count,
    .lap_start_s = s.lap_start_s,
    .lap_start_m = s.lap_start_m,
    .saved_at = time(NULL),
  };
  persist_write_data(RUN_KEY, &r, sizeof(r));
}

int main(void) {
  load_run();
  if (persist_exists(SETTINGS_KEY)) {
    persist_read_data(SETTINGS_KEY, &s_settings, sizeof(s_settings));
    light_enable(s_settings.backlight);
  }
  s.map_page = true;
  s.zoom = 16;
  // Open on the activity list unless an activity is already under way.
  s.picking = !s.started;
  s.pick = s_settings.mode;
  s_front = gbitmap_create_blank(GSize(MAP_W, MAP_H), GBitmapFormat8Bit);
  s_back = gbitmap_create_blank(GSize(MAP_W, MAP_H), GBitmapFormat8Bit);
  s_arrow = gpath_create(&ARROW_INFO);
  s_play = gpath_create(&PLAY_INFO);
  s_heart = gpath_create(&HEART_INFO);
  int steps = (int)health_service_sum_today(HealthMetricStepCount);
  for (int i = 0; i < CADENCE_WINDOW_S; i++) s.steps[i] = steps;

  s_window = window_create();
  window_set_click_config_provider(s_window, click_config);
  window_set_window_handlers(s_window, (WindowHandlers){
    .load = window_load,
    .unload = window_unload,
  });
  window_stack_push(s_window, true);

  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_open(app_message_inbox_size_maximum(), 64);
  // The companion's sync on app open arrives before the inbox is open and is
  // rejected, so ask for it again now.
  send_state(HELLO_KEY);
  app_timer_register(COMPANION_WAIT_MS, companion_check, NULL);
  tick_timer_service_subscribe(SECOND_UNIT, tick_handler);
  health_service_set_heart_rate_sample_period(1);

  app_event_loop();
  light_enable(false);
  save_run();

  health_service_set_heart_rate_sample_period(0);
  window_destroy(s_window);
  gpath_destroy(s_arrow);
  gpath_destroy(s_play);
  gpath_destroy(s_heart);
  gbitmap_destroy(s_back);
  gbitmap_destroy(s_front);
}
