-- CloudStreamPC: "Passer l'intro" and "Épisode suivant" buttons.
-- The app fills in the skip segments and the next episode through the JSON IPC
-- (script-message-to cloudstreampc ...) and is told about clicks with `script-message cspc-next`.

local mp = require 'mp'
local utils = require 'mp.utils'
local options = require 'mp.options'

local opts = {
    has_next = false,
    auto_next = true,
    auto_skip = false,
    skip_button = true,
}
options.read_options(opts, 'cloudstreampc')

local COUNTDOWN = 10      -- seconds before the next episode starts during the credits
local NEAR_END = 40       -- without known credits, offer the next episode this close to the end

local state = {
    segments = {},        -- from the app (AniSkip): { kind = 'op'|'recap'|'ed', start, stop }
    chapters = {},        -- same shape, guessed from the chapter titles
    pos = nil,
    duration = nil,
    w = 0, h = 0,
    mouse_x = -1, mouse_y = -1,
    button = nil,         -- what is drawn: { kind, label, x, y, w, h, seg }
    hovered = false,
    countdown_start = nil,
    loading = false,
    loading_since = 0,
    auto_skipped = {},
    message = nil,
    message_until = 0,
}

local overlay = mp.create_osd_overlay('ass-events')

local function msg(text, seconds)
    state.message = text
    state.message_until = mp.get_time() + (seconds or 3)
end

---------------------------------------------------------------------------
-- Segments
---------------------------------------------------------------------------

local CHAPTER_KINDS = {
    { 'ed', { 'générique de fin', 'generique de fin', 'credits', 'crédits', 'ending', 'outro', '^ed$', '^ed ', 'エンディング' } },
    { 'recap', { 'recap', 'récap', 'résumé', 'previously' } },
    { 'op', { 'intro', 'opening', 'générique', 'generique', 'ouverture', '^op$', '^op ', 'オープニング' } },
}

local function chapter_kind(title)
    title = (title or ''):lower()
    for _, entry in ipairs(CHAPTER_KINDS) do
        for _, pattern in ipairs(entry[2]) do
            if title:find(pattern) then return entry[1] end
        end
    end
    return nil
end

local function update_chapters()
    local list = mp.get_property_native('chapter-list') or {}
    local duration = mp.get_property_number('duration')
    state.chapters = {}
    for i, chapter in ipairs(list) do
        local kind = chapter_kind(chapter.title)
        local stop = list[i + 1] and list[i + 1].time or duration
        if kind and stop and stop > chapter.time then
            table.insert(state.chapters, { kind = kind, start = chapter.time, stop = stop })
        end
    end
end

local function current_segment()
    local pos = state.pos
    if not pos then return nil end
    for _, source in ipairs({ state.segments, state.chapters }) do
        for _, seg in ipairs(source) do
            if pos >= seg.start and pos < seg.stop then return seg end
        end
    end
    return nil
end

---------------------------------------------------------------------------
-- Actions
---------------------------------------------------------------------------

local function trigger_next(reason)
    if state.loading or not opts.has_next then return end
    mp.msg.info(string.format('épisode suivant (%s) à %s / %s', tostring(reason), tostring(state.pos), tostring(state.duration)))
    state.loading = true
    state.loading_since = mp.get_time()
    state.countdown_start = nil
    -- No pause here: pausing then changing key bindings makes uosc spin (mpv 0.37); the credits keep playing.
    mp.commandv('script-message', 'cspc-next')
end

local function skip(seg)
    mp.commandv('seek', tostring(seg.stop), 'absolute')
end

local function activate()
    local button = state.button
    if not button then return end
    if button.kind == 'skip' then
        skip(button.seg)
    elseif button.kind == 'next' then
        trigger_next('bouton')
    end
end

---------------------------------------------------------------------------
-- Drawing
---------------------------------------------------------------------------

local function ass_color(rgb)
    return string.format('&H%s%s%s&', rgb:sub(5, 6), rgb:sub(3, 4), rgb:sub(1, 2))
end

local function rounded_rect(x, y, w, h, r)
    local x2, y2 = x + w, y + h
    return string.format(
        'm %d %d l %d %d b %d %d %d %d %d %d l %d %d b %d %d %d %d %d %d l %d %d b %d %d %d %d %d %d l %d %d b %d %d %d %d %d %d',
        x + r, y, x2 - r, y, x2, y, x2, y, x2, y + r,
        x2, y2 - r, x2, y2, x2, y2, x2 - r, y2,
        x + r, y2, x, y2, x, y2, x, y2 - r,
        x, y + r, x, y, x, y, x + r, y)
end

local function shape(path, fill, alpha, extra)
    return string.format('{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a&H%s&%s\\p1}%s{\\p0}', ass_color(fill), alpha, extra or '', path)
end

local function outline(path, color, width)
    return string.format('{\\an7\\pos(0,0)\\shad0\\1a&HFF&\\3c%s\\bord%d\\p1}%s{\\p0}', ass_color(color), width, path)
end

local function text(x, y, size, color, value, extra)
    return string.format('{\\an5\\pos(%d,%d)\\fs%d\\b1\\bord0\\shad%s\\4a&H80&\\1c%s%s}%s',
        x, y, size, '1', ass_color(color), extra or '', value)
end

local function compute_button()
    if state.loading then
        return { kind = 'loading', label = "Chargement de l'épisode suivant…" }
    end
    if not state.pos then return nil end
    local seg = current_segment()
    if seg and (seg.kind == 'op' or seg.kind == 'recap') and opts.skip_button and seg.stop - state.pos > 1 then
        return { kind = 'skip', label = seg.kind == 'recap' and 'Passer le récap' or "Passer l'intro", seg = seg }
    end
    if opts.has_next then
        local credits = seg and seg.kind == 'ed'
        local near_end = state.duration and state.duration > 120 and state.duration - state.pos <= NEAR_END
        if credits or near_end then
            return { kind = 'next', label = 'Épisode suivant', countdown = credits and opts.auto_next }
        end
    end
    return nil
end

local function set_click_binding(enabled)
    if enabled then
        mp.add_forced_key_binding('MBTN_LEFT', 'cspc-click', activate)
    else
        mp.remove_key_binding('cspc-click')
    end
end

-- Enter activates the visible button. Bound once: adding and removing bindings while paused makes uosc spin.
mp.add_forced_key_binding('ENTER', 'cspc-enter', activate)
mp.add_forced_key_binding('KP_ENTER', 'cspc-kp-enter', activate)

local function render()
    local now = mp.get_time()
    if state.loading and now - state.loading_since > 60 then
        state.loading = false
        msg("Impossible de charger l'épisode suivant", 4)
    end

    local w, h = state.w, state.h
    if w <= 0 or h <= 0 then return end
    local s = math.max(h / 1080, 0.6)
    local lines = {}

    local button = compute_button()
    if button and button.kind == 'next' and button.countdown then
        state.countdown_start = state.countdown_start or now
    elseif not (button and button.kind == 'next') then
        state.countdown_start = nil
    end

    if button then
        local font = math.floor(26 * s)
        local label = button.label
        local progress = nil
        if button.kind == 'next' and state.countdown_start then
            local remaining = math.max(0, COUNTDOWN - (now - state.countdown_start))
            label = string.format('%s · %d s', label, math.ceil(remaining))
            progress = 1 - remaining / COUNTDOWN
            if remaining <= 0 then trigger_next('compte à rebours') end
        end
        local chars = select(2, label:gsub('[^\128-\191]', ''))  -- UTF-8 characters, not bytes
        local bw = math.floor((chars * 0.5 * font) + 64 * s)
        local bh = math.floor(64 * s)
        local bx = math.floor(w - bw - 64 * s)
        local by = math.floor(h - bh - 150 * s)
        button.x, button.y, button.w, button.h = bx, by, bw, bh
        local hovered = button.kind ~= 'loading' and state.mouse_x >= bx and state.mouse_x <= bx + bw
            and state.mouse_y >= by and state.mouse_y <= by + bh
        local r = math.floor(6 * s)
        local path = rounded_rect(bx, by, bw, bh, r)
        if hovered then
            table.insert(lines, shape(path, 'ffffff', '00'))
            table.insert(lines, text(bx + bw / 2, by + bh / 2, font, '000000', label, '\\shad0'))
        else
            table.insert(lines, shape(path, '141414', '40'))
            if progress then
                local clip = string.format('\\clip(%d,%d,%d,%d)', bx, by, bx + math.floor(bw * progress), by + bh)
                table.insert(lines, shape(path, 'ffffff', 'B0', clip))
            end
            if button.kind ~= 'loading' then
                table.insert(lines, outline(path, 'ffffff', math.max(1, math.floor(2 * s))))
            end
            table.insert(lines, text(bx + bw / 2, by + bh / 2, font, 'ffffff', label))
        end
        if hovered ~= state.hovered then
            state.hovered = hovered
            set_click_binding(hovered)
        end
    elseif state.hovered then
        state.hovered = false
        set_click_binding(false)
    end
    state.button = button

    if state.message and now < state.message_until then
        table.insert(lines, text(w / 2, math.floor(h - 110 * s), math.floor(24 * s), 'ffffff', state.message))
    end

    overlay.res_x = w
    overlay.res_y = h
    overlay.data = table.concat(lines, '\n')
    overlay:update()
end

---------------------------------------------------------------------------
-- Events
---------------------------------------------------------------------------

mp.observe_property('time-pos', 'number', function(_, value)
    state.pos = value
    if value and opts.auto_skip then
        local seg = current_segment()
        if seg and (seg.kind == 'op' or seg.kind == 'recap') then
            local key = seg.kind .. ':' .. math.floor(seg.start)
            if not state.auto_skipped[key] then
                state.auto_skipped[key] = true
                skip(seg)
                msg(seg.kind == 'recap' and 'Récap passé' or 'Intro passée', 2)
            end
        end
    end
end)
mp.observe_property('duration', 'number', function(_, value) state.duration = value; update_chapters() end)
mp.observe_property('chapter-list', 'native', update_chapters)
mp.observe_property('osd-dimensions', 'native', function(_, dim)
    if dim then state.w, state.h = dim.w, dim.h end
end)
mp.observe_property('mouse-pos', 'native', function(_, mouse)
    if mouse then
        state.mouse_x = mouse.hover and mouse.x or -1
        state.mouse_y = mouse.hover and mouse.y or -1
    end
end)

-- `--keep-open` keeps the window at the end: play the next episode or quit.
mp.observe_property('eof-reached', 'bool', function(_, eof)
    if not eof then return end
    if opts.has_next and opts.auto_next then
        trigger_next('fin de la vidéo')
    elseif not opts.has_next then
        mp.command('quit')
    end
end)

mp.register_script_message('segments', function(json)
    local list = utils.parse_json(json or '[]') or {}
    state.segments = {}
    for _, seg in ipairs(list) do
        if seg.kind and seg.start and seg.stop and seg.stop > seg.start then
            table.insert(state.segments, { kind = seg.kind, start = seg.start, stop = seg.stop })
        end
    end
end)

-- A new episode was loaded in this window.
mp.register_script_message('episode', function(json)
    local info = utils.parse_json(json or '{}') or {}
    opts.has_next = info.has_next == true
    state.segments = {}
    state.auto_skipped = {}
    state.countdown_start = nil
    state.loading = false
    state.pos = nil
    if info.label and info.label ~= '' then msg(info.label, 3) end
end)

mp.register_script_message('next-failed', function(reason)
    state.loading = false
    msg(reason and reason ~= '' and reason or "Aucune source disponible pour l'épisode suivant", 5)
end)

mp.register_script_message('next', function() trigger_next('commande') end)
mp.add_key_binding('N', 'cspc-next-episode', function() trigger_next('touche N') end)

mp.add_periodic_timer(0.2, render)
