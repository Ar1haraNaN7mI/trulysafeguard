package app.local.privacyguard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class NoticePolicy {
    static final String PREFIX = "notice.rule.";
    static final String KEYWORDS_KEY = "notice.extra_keywords";
    static final String PROMOTIONS = "promotions";
    static final String MUTE = "mute";
    static final int MAX_TERMS = 32;
    static final int MAX_TERM_LENGTH = 48;

    private static final String[] OFFERS = {
        "限时优惠", "专属优惠", "优惠券", "大促", "满减", "促销", "秒杀", "限时抢购",
        "限时折扣", "领券", "降价提醒", "特价", "今日特惠", "会员特惠", "专享折扣",
        "领取福利", "限时福利", "新人福利", "购物津贴", "限量抢购", "闪购",
        "limited time offer", "limited-time offer", "flash sale", "promo code",
        "coupon", "special offer", "exclusive discount"
    };
    private static final String[] SENSITIVE = {
        "验证码", "校验码", "动态口令", "动态密码", "登录代码", "安全代码", "一次性密码",
        "verification code", "security code", "one-time code", "one time code",
        "one-time password", "one time password", "login code", "passcode", " otp",
        "交易提醒", "交易通知", "交易成功", "扣款", "入账", "到账", "收款通知", "收款成功",
        "支付提醒", "支付成功", "支付失败", "付款成功", "退款", "转账", "账单", "还款",
        "transaction alert", "transaction notification", "payment received", "payment successful",
        "私信", "新消息", "给你发来", "发来一条", "direct message", "sent you a message"
    };

    private NoticePolicy() {}

    static boolean enabledMode(String mode) {
        return PROMOTIONS.equals(mode) || MUTE.equals(mode);
    }

    static boolean criticalCategory(String category) {
        return "call".equals(category) || "missed_call".equals(category) || "alarm".equals(category)
                || "car_emergency".equals(category) || "car_warning".equals(category)
                || "emergency".equals(category);
    }

    static boolean messageCategory(String category) {
        return "msg".equals(category) || "email".equals(category) || "social".equals(category);
    }

    static List<String> customTerms(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String line : value.split("\\r?\\n")) {
            String term = line.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty()) result.add(term);
        }
        return new ArrayList<>(result);
    }

    static String validate(List<String> terms) {
        if (terms.size() > MAX_TERMS) return "最多可添加 " + MAX_TERMS + " 个关键词";
        for (String term : terms) {
            if (term.codePointCount(0, term.length()) > MAX_TERM_LENGTH) {
                return "每个关键词最多 " + MAX_TERM_LENGTH + " 个字符";
            }
        }
        return null;
    }

    static boolean matchesPromotion(String text, List<String> extras) {
        String normalized = " " + text.toLowerCase(Locale.ROOT);
        for (String sensitive : SENSITIVE) if (normalized.contains(sensitive)) return false;
        for (String offer : OFFERS) if (normalized.contains(offer)) return true;
        for (String word : extras) if (normalized.contains(word)) return true;
        return false;
    }
}
