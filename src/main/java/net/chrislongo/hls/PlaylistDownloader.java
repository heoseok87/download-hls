/*
 * Copyright (c) Christopher A Longo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.chrislongo.hls;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaylistDownloader {
	private URL url;
	private List<String> playlist;
	private Crypto crypto;
	private String cookie;

	private static String EXT_X_KEY = "#EXT-X-KEY";
	private static final String BANDWIDTH = "BANDWIDTH";

	Pattern pattern = Pattern.compile("BANDWIDTH=([0-9]+)");

	public PlaylistDownloader(String playlistUrl) throws MalformedURLException {
		this(playlistUrl, null);
	}

	public PlaylistDownloader(String playlistUrl, String cookie) throws MalformedURLException {
		this.url = new URL(playlistUrl);
		this.playlist = new ArrayList<String>();
		this.cookie = cookie;
	}

	public void download(String outfile) throws IOException {
		this.download(outfile, null);
	}

	public void download(String outfile, String key) throws IOException {
		fetchPlaylist();

		while (new File(outfile).exists()) {
			Path path = Paths.get(outfile);
			outfile = Paths.get(path.getParent().toString(), path.getFileName().toString().replace(".", "_."))
					.toString();
		}

		System.out.println("Download to " + outfile);

		this.crypto = new Crypto(getBaseUrl(this.url), key);

		List<URL> urlList = new ArrayList<URL>();

		for (String line : playlist) {
			line = line.trim();

			if (line.startsWith(EXT_X_KEY)) {
				crypto.updateKeyString(line);

				// System.out.printf("\rCurrent Key: %s \n", crypto.getCurrentKey());
				// System.out.printf("Current IV: %s\n", crypto.getCurrentIV());
			} else if (line.length() > 0 && !line.startsWith("#")) {
				URL segmentUrl;

				if (!line.startsWith("http")) {
					String baseUrl = getBaseUrl(this.url);
					segmentUrl = new URL(baseUrl + line);
				} else {
					segmentUrl = new URL(line);
				}
				urlList.add(segmentUrl);
			}
		}

		for (int i = 0; i < urlList.size(); i++) {
			try {
				downloadInternal(urlList.get(i), outfile);
				System.out.println(String.format("%d / %d", i + 1, urlList.size()));
			} catch (IOException e) {
				if (urlList.size() - i < 2) {
					// 마지막 파일만 실패한 경우 성공으로 간주
					continue;
				} else {
					throw e;
				}
			}
		}

		System.out.println("\nDone.");
	}

	private void downloadInternal(URL segmentUrl, String outFile) throws IOException {
		byte[] buffer = new byte[512];

		URLConnection connection = segmentUrl.openConnection();
		if (hasCookie()) {
			// System.out.println("Set cookie : " + this.cookie);
			connection.setRequestProperty("Cookie", this.cookie);
		}

		InputStream is = crypto.hasKey() ? crypto.wrapInputStream(connection.getInputStream())
				: connection.getInputStream();

		FileOutputStream out;

		if (outFile != null) {
			File file = new File(outFile);
			out = new FileOutputStream(outFile, file.exists());
		} else {
			String path = segmentUrl.getPath();
			int pos = path.lastIndexOf('/');
			out = new FileOutputStream(path.substring(++pos), false);
		}

		// System.out.printf("Downloading segment: %s\r", segmentUrl);

		int read;

		while ((read = is.read(buffer)) >= 0) {
			out.write(buffer, 0, read);
		}

		is.close();
		out.close();
	}

	private String getBaseUrl(URL url) {
		String urlString = url.toString();
		int index = urlString.lastIndexOf('/');
		return urlString.substring(0, ++index);
	}

	private void fetchPlaylist() throws IOException {
		URLConnection connection = url.openConnection();
		if (hasCookie()) {
			connection.setRequestProperty("Cookie", this.cookie);
		}
		BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
		boolean isMaster = false;
		long maxRate = 0L;
		int maxRateIndex = 0;

		String line;
		int index = 0;

		while ((line = reader.readLine()) != null) {
			playlist.add(line);

			if (line.contains(BANDWIDTH))
				isMaster = true;

			if (isMaster && line.contains(BANDWIDTH)) {
				try {
					// int pos = line.lastIndexOf("=");
					long bandwidth = 0;
					Matcher matcher = this.pattern.matcher(line);
					if (matcher.find()) {
						bandwidth = Long.parseLong(matcher.group(1));
					}

					maxRate = Math.max(bandwidth, maxRate);

					if (bandwidth == maxRate)
						maxRateIndex = index + 1;
				} catch (NumberFormatException ignore) {
				}
			}

			index++;
		}

		reader.close();

		if (isMaster) {
			System.out.printf("Found master playlist, fetching highest stream at %dKb/s\n", maxRate / 1024);
			this.url = updateUrlForSubPlaylist(playlist.get(maxRateIndex));
			this.playlist.clear();

			fetchPlaylist();
		}
	}

	private URL updateUrlForSubPlaylist(String sub) throws MalformedURLException {
		String newUrl;

		if (!sub.startsWith("http")) {
			newUrl = getBaseUrl(this.url) + sub;
		} else {
			newUrl = sub;
		}

		return new URL(newUrl);
	}

	public boolean hasCookie() {
		return this.cookie != null && !this.cookie.trim().isEmpty();
	}
}
