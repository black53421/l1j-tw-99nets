package l1j.server.server.utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public final class DailyTimeWindow {
	private final int _startMinute;
	private final int _endMinute;

	private DailyTimeWindow(int startMinute, int endMinute) {
		_startMinute = startMinute;
		_endMinute = endMinute;
	}

	public boolean isActive(Calendar time) {
		if (time == null) {
			return false;
		}

		int minuteOfDay = (time.get(Calendar.HOUR_OF_DAY) * 60)
				+ time.get(Calendar.MINUTE);
		if (_startMinute < _endMinute) {
			return (minuteOfDay >= _startMinute) && (minuteOfDay < _endMinute);
		}
		return (minuteOfDay >= _startMinute) || (minuteOfDay < _endMinute);
	}

	public static List<DailyTimeWindow> parseList(String value) {
		if (value == null || value.trim().length() == 0) {
			return Collections.emptyList();
		}

		List<DailyTimeWindow> result = new ArrayList<DailyTimeWindow>();
		String[] windows = value.split(",");
		for (String window : windows) {
			String trimmed = window.trim();
			if (trimmed.length() == 0) {
				throw new IllegalArgumentException("Empty daily time window");
			}

			String[] range = trimmed.split("-");
			if (range.length != 2) {
				throw new IllegalArgumentException("Invalid daily time window: " + trimmed);
			}

			int startMinute = parseMinuteOfDay(range[0].trim());
			int endMinute = parseMinuteOfDay(range[1].trim());
			if (startMinute == endMinute) {
				throw new IllegalArgumentException("Daily time window start and end cannot be equal: " + trimmed);
			}
			result.add(new DailyTimeWindow(startMinute, endMinute));
		}
		return Collections.unmodifiableList(result);
	}

	private static int parseMinuteOfDay(String value) {
		String[] parts = value.split(":");
		if (parts.length != 2) {
			throw new IllegalArgumentException("Invalid time: " + value);
		}

		try {
			int hour = Integer.parseInt(parts[0]);
			int minute = Integer.parseInt(parts[1]);
			if ((hour < 0) || (hour > 23) || (minute < 0) || (minute > 59)) {
				throw new IllegalArgumentException("Invalid time: " + value);
			}
			return (hour * 60) + minute;
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid time: " + value, e);
		}
	}
}
