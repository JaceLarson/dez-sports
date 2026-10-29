#!/usr/bin/env python3
"""Builds trailer/DezSports-trailer.mp4 (1080x1920) from the clips recorded with rec.sh."""
import os
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CLIPS = os.path.join(HERE, "clips")
ICON = os.path.join(HERE, "..", "branding", "icon_circle.png")
OUT = os.path.join(HERE, "DezSports-trailer.mp4")
FONT = "/usr/share/fonts/noto/NotoSans-{}.ttf"
BG = "0x050507"
RED = "0xFF3B30"
W, H, FPS = 1080, 1920, 30

# (clip, start, duration, layout, title, subtitle)
SHOTS = [
    ("home", 1.0, 4.2, "inner", "Your teams, front and center", "Mammoth · Jazz · Lions · BYU, in team colors"),
    ("home", 6.2, 5.0, "inner", "Big Games Tonight", "Only games on national TV, with the channel"),
    ("compact", 1.6, 4.0, "inner", "Detailed or Compact", "Every score in a league at a glance"),
    ("arena_nba", 0.3, 7.5, "inner", "Gamecast Arena", "8-bit players with real jersey numbers"),
    ("arena_nfl", 0.5, 7.0, "inner", "Broadcast-style football", "Line of scrimmage, first-down line, every snap"),
    ("arena_nhl", 0.0, 6.5, "inner", "Goal lights included", "Every shot, save and goal on the ice"),
    ("arena_mlb", 0.2, 7.0, "inner", "Pitch by pitch", "Then the hit, the runners and the crowd"),
    ("box", 0.5, 5.0, "inner", "The whole box score", "On one screen, with leader pills and team colors"),
    ("overview", 3.6, 3.2, "inner", "Momentum & win probability", "Shot charts and team stat duels"),
    ("player", 0.5, 6.0, "inner", "Stat nerd mode", "Game logs heat-mapped against season averages"),
    ("standings", 0.8, 4.0, "inner", "Standings", "Divisions and conferences for every league"),
    ("sim", 0.6, 6.5, "inner", "Playoff Simulator", "Pick winners and watch the odds move"),
    ("h2h", 0.5, 4.5, "inner", "Head-to-Head", "Every meeting, streak and margin"),
    ("fold_home", 0.5, 6.5, "outer", "Folded, it's a phone app", "Swipe through every player's statline"),
    ("fold_pin", 1.0, 3.2, "outer", "Pin any game", "Its live score follows you"),
    ("lock", 0.0, 3.2, "outer", "Live on your lock screen", "Score, clock and logos at a glance"),
    ("widget", 0.5, 3.6, "widget", "Home screen widget", "All your teams, always updated"),
]


def run(args):
    subprocess.run(args, check=True)


def textfile(tmp, s):
    fd, path = tempfile.mkstemp(dir=tmp, suffix=".txt")
    with os.fdopen(fd, "w") as f:
        f.write(s)
    return path


def text(tmp, s, font, size, color, y, fade_at=0.15):
    return (f"drawtext=fontfile={FONT.format(font)}:textfile={textfile(tmp, s)}:fontsize={size}:fontcolor={color}"
            f":x=(w-text_w)/2:y={y}:alpha='min(1,max(0,(t-{fade_at})/0.35))'")


def footer(tmp):
    return text(tmp, "DEZ SPORTS", "BlackItalic", 34, "0x8A8A92", 1830, 0)


def encode(tmp, idx, inputs, graph, dur):
    out = os.path.join(tmp, f"seg{idx:02d}.mp4")
    run(["ffmpeg", "-v", "error", "-y", *inputs,
         "-f", "lavfi", "-t", str(dur), "-i", "anullsrc=r=48000:cl=stereo",
         "-filter_complex", graph, "-map", "[v]", "-map", f"{len([a for a in inputs if a == '-i'])}:a",
         "-t", str(dur), "-r", str(FPS), "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-b:a", "96k", out])
    return out


def shot(tmp, idx, clip, start, dur, layout, title, sub):
    src = os.path.join(CLIPS, clip + ".mp4")
    if layout == "inner":
        fit, x, y, fw, fh = "scale=1000:-2", 40, 560, 1000, 1036
    elif layout == "outer":
        fit, x, y, fw, fh = "scale=-2:1250", 264, 450, 552, 1250
    else:  # just the widget off the home screen
        fit, x, y, fw, fh = "crop=1080:660:0:160,scale=1000:-2", 40, 700, 1000, 612
    ty = 240 if layout == "outer" else 330
    fade = f"fade=t=in:st=0:d=0.3,fade=t=out:st={dur - 0.3:.2f}:d=0.3"
    graph = (
        f"color=c={BG}:s={W}x{H}:r={FPS}:d={dur}[bg];"
        f"[0:v]fps={FPS},{fit},tpad=stop_mode=clone:stop_duration={dur},setpts=PTS-STARTPTS[f];"
        f"[bg]drawbox=x={x - 3}:y={y - 3}:w={fw + 6}:h={fh + 6}:color=0x2A2A30:t=fill[bb];"
        f"[bb][f]overlay={x}:{y}:shortest=1,"
        f"drawbox=x=500:y={ty - 40}:w=80:h=7:color={RED}:t=fill,"
        f"{text(tmp, title, 'Black', 66, 'white', ty)},"
        f"{text(tmp, sub, 'Regular', 36, '0xA4A4AC', ty + 95, 0.3)},"
        f"{footer(tmp)},{fade}[v]"
    )
    return encode(tmp, idx, ["-ss", str(start), "-i", src], graph, dur)


def card(tmp, idx, dur, lines, icon_size, icon_y):
    draws = ",".join(text(tmp, s, font, size, color, y, at) for s, font, size, color, y, at in lines)
    graph = (
        f"color=c={BG}:s={W}x{H}:r={FPS}:d={dur}[bg];"
        f"[0:v]scale={icon_size}:{icon_size},format=rgba,fade=t=in:st=0.1:d=0.5:alpha=1[ic];"
        f"[bg][ic]overlay=(W-w)/2:{icon_y}:shortest=0,"
        f"drawbox=x=440:y={icon_y + icon_size + 60}:w=200:h=8:color={RED}:t=fill,"
        f"{draws},fade=t=in:st=0:d=0.3,fade=t=out:st={dur - 0.4:.2f}:d=0.4,trim=duration={dur}[v]"
    )
    return encode(tmp, idx, ["-loop", "1", "-t", str(dur), "-i", ICON], graph, dur)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        segs = [card(tmp, 0, 3.2, [
            ("DEZ SPORTS", "BlackItalic", 132, "white", 1000, 0.3),
            ("Every score. Every stat.", "Bold", 50, "0xE8E8EE", 1185, 0.8),
            ("Built for the Pixel Fold.", "Regular", 44, "0xA4A4AC", 1260, 1.2),
        ], 380, 470)]
        for i, s in enumerate(SHOTS, start=1):
            segs.append(shot(tmp, i, *s))
            print("built", s[0], s[4])
        segs.append(card(tmp, len(SHOTS) + 1, 3.8, [
            ("Dez Sports", "Black", 110, "white", 1010, 0.2),
            ("NBA · NFL · NHL · MLB · College · Golf", "Bold", 42, "0xE8E8EE", 1170, 0.6),
            ("Personal Android app · v0.6", "Regular", 38, "0xA4A4AC", 1240, 1.0),
        ], 300, 520))
        lst = os.path.join(tmp, "list.txt")
        with open(lst, "w") as f:
            f.writelines(f"file '{p}'\n" for p in segs)
        run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", OUT])
    print(OUT)


if __name__ == "__main__":
    main()
