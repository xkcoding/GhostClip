use objc2_app_kit::NSPasteboard;
use objc2_foundation::NSString;

/// 读取系统剪贴板纯文本内容
pub fn read_clipboard() -> Option<String> {
    let pasteboard = NSPasteboard::generalPasteboard();
    let pb_type = NSString::from_str("public.utf8-plain-text");
    pasteboard.stringForType(&pb_type).map(|s| s.to_string())
}

/// 写入纯文本到系统剪贴板
pub fn write_clipboard(text: &str) -> bool {
    let pasteboard = NSPasteboard::generalPasteboard();
    pasteboard.clearContents();
    let ns_string = NSString::from_str(text);
    let pb_type = NSString::from_str("public.utf8-plain-text");
    pasteboard.setString_forType(&ns_string, &pb_type)
}

/// 获取剪贴板 changeCount，用于检测变化
pub fn get_change_count() -> isize {
    let pasteboard = NSPasteboard::generalPasteboard();
    pasteboard.changeCount()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_clipboard_roundtrip() {
        let test_text = "GhostClip spike test 测试";
        assert!(write_clipboard(test_text));
        let result = read_clipboard();
        assert_eq!(result, Some(test_text.to_string()));
    }

    #[test]
    fn test_change_count() {
        let count1 = get_change_count();
        write_clipboard("change count test");
        let count2 = get_change_count();
        assert!(count2 > count1, "changeCount should increase after write");
    }
}
