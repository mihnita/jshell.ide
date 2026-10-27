import java.util.*;
import java.text.*;
// import com.google.gson.*;

// Arrays.asList(Locale.getAvailableLocales())
//     .stream()
//     .map(Locale::toLanguageTag)
//     .sorted()
//     .forEach(System.out::println)

String[] days = { "Mo", "Tu", "We" };

// var gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

// System.out.println(gson.toJson(locales));
// System.err.println(gson.toJson(days));

static record LocInfo(String id, String name, String nativeName) implements Comparable<LocInfo> {
  static LocInfo of(Locale loc) { return new LocInfo(loc.toLanguageTag(), loc.getDisplayName(), loc.getDisplayName(loc)); }
  @Override public int compareTo(LocInfo other) { return this.id.compareTo(other.id); }
}

var allLoc = Locale.availableLocales()
    .map(LocInfo::of)
    .sorted()
    .toList();

// System.out.println(gson.toJson(allLoc));
allLoc.stream().filter(l-> l.id().length() > 12).forEach(System.out::println);
