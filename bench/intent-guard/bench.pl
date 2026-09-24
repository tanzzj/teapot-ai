#!/usr/bin/perl
# Intent-guard benchmark: JEV (System One) vs LLM judge (dashscope qwen3.8-flash).
# Payloads replicate JevIntentGuardMiddleware / LlmIntentGuardMiddleware exactly.
# No CPAN deps: HTTP::Tiny (core) + hand-rolled JSON emit/parse.
use strict;
use warnings;
use IO::Handle;
use Time::HiRes qw(time sleep);

my ($dir, $out_file) = @ARGV;
die "usage: bench.pl <dir with rules.txt/cases.tsv> results.jsonl\n" if !$dir || !$out_file;

my $threshold = $ENV{BENCH_THRESHOLD} // 0.5;
my $jev_model = $ENV{BENCH_JEV_MODEL} // "jev-latest";
my $llm_model = $ENV{BENCH_LLM_MODEL} // "qwen3.8-flash";
my $jev_key   = $ENV{TYPESAFE_API_KEY}   or die "TYPESAFE_API_KEY missing";
my $ds_key    = $ENV{DASHSCOPE_API_KEY}  or die "DASHSCOPE_API_KEY missing";

sub read_lines {
    my ($f) = @_;
    open my $fh, '<:raw', $f or die "open $f: $!";
    my @l = map { s/\r?\n$//r } <$fh>;
    close $fh;
    return \@l;
}

my @rules = grep { length } @{ read_lines("$dir/rules.txt") };
die "no rules" unless @rules;

sub esc {
    my ($s) = @_;
    $s =~ s/\\/\\\\/g;
    $s =~ s/"/\\"/g;
    $s =~ s/\n/\\n/g;
    $s =~ s/\r/\\r/g;
    $s =~ s/\t/\\t/g;
    return $s;
}

my $rule_lines = join('', map { "- $_\n" } @rules);
$rule_lines =~ s/\n/\\n/g;    # embed as one JSON string

# JevIntentGuardMiddleware#joinedInstructions
my $jev_instr =
    "You are an intent guard. Decide whether the latest user request in"
  . " `state.userRequest` is allowed by the following guard rules. Deny a"
  . " request when it matches a rejected intent, or when the rules define"
  . " accepted intents and it matches none of them. Guard rules:\\n$rule_lines";

# LlmIntentGuardMiddleware judgePrompt
my $llm_sys =
    "你是一个意图守卫。判断用户的最新请求是否被下面的守卫规则允许。"
  . "规则中「接受的意图」是白名单，「拒绝的意图」是黑名单："
  . "请求命中任一拒绝意图即不允许；若存在接受的意图而请求未命中任何一条，"
  . "也不允许。只输出 ACCEPT（允许）或 REJECT（不允许），不要输出其他内容。"
  . "守卫规则:\\n$rule_lines";

my $http_j_timeout = 8;
my $http_l_timeout = 25;
my $reqf = "/tmp/gb-req.$$.json";
my $resf = "/tmp/gb-res.$$.json";

# curl-based HTTPS (server perl lacks IO::Socket::SSL). Latency = curl time_total.
sub post_json {
    my ($url, $auth, $body, $maxtime) = @_;
    open my $rf, '>:raw', $reqf or die "write $reqf: $!";
    print $rf $body;
    close $rf;
    my @cmd = ("curl", "-sS", "--max-time", $maxtime,
               "-o", $resf, "-w", "%{http_code} %{time_total} %{time_appconnect}",
               "-X", "POST", $url,
               "-H", "Authorization: Bearer $auth",
               "-H", "Content-Type: application/json",
               "-H", "Accept: application/json",
               "--data-binary", "\@$reqf");
    my $w = '';
    if (open my $ch, '-|', @cmd) {
        local $/;
        $w = <$ch> // '';
        close $ch;
    }
    chomp $w;
    my ($code, $tt, $tls) = split / /, $w;
    my $ms = defined $tt ? int($tt * 1000) : -1;
    my $tlsms = defined $tls ? int($tls * 1000) : -1;
    my $content = '';
    if (-e $resf) {
        open my $sf, '<:raw', $resf or die;
        $content = do { local $/; <$sf> };
        close $sf;
    }
    return ($code // '0', $ms, $content, $tlsms);
}

sub jev_ask {
    my ($text) = @_;
    my $body = '{"model":"' . esc($jev_model) . '","state":{"userRequest":"' . esc($text)
        . '"},"questions":{"intent":{"type":"noul","instructions":"' . $jev_instr
        . '","criteria":{"true":"The request is allowed by the guard rules.",'
        . '"false":"The request is not allowed by the guard rules."}}}}';
    my ($code, $ms, $resp, $tls) = post_json("https://api.typesafe.ai/v1/systemone",
        $jev_key, $body, $http_j_timeout);
    if ($code !~ /^2/) {
        return { ms => $ms, err => "http $code " . substr($resp, 0, 140) };
    }
    if ($resp !~ /"noul"\s*:\s*([0-9.eE+-]+)/) {
        return { ms => $ms, err => "no noul: " . substr($resp, 0, 120) };
    }
    my $noul = $1 + 0;
    return { ms => $ms, tls => $tls, noul => $noul, dec => ($noul < $threshold ? "REJECT" : "ACCEPT") };
}

sub llm_ask {
    my ($text) = @_;
    my $body = '{"model":"' . esc($llm_model) . '","messages":[{"role":"system","content":"'
        . $llm_sys . '"},{"role":"user","content":"' . esc("用户请求：" . $text)
        . '"}],"max_tokens":10,"enable_thinking":false}';
    my ($code, $ms, $resp, $tls) = post_json(
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        $ds_key, $body, $http_l_timeout);
    if ($code !~ /^2/) {
        return { ms => $ms, err => "http $code " . substr($resp, 0, 140) };
    }
    my $content = $resp =~ /"content"\s*:\s*"((?:[^"\\]|\\.)*)"/ ? $1 : '';
    my $upper = uc $content;
    my $dec;
    if    ($upper =~ /REJECT/) { $dec = "REJECT" }
    elsif ($upper =~ /ACCEPT/) { $dec = "ACCEPT" }
    else                       { $dec = "UNPARSED" }   # production: failOpen -> pass
    return { ms => $ms, tls => $tls, out => $content, dec => $dec };
}

open my $out, '>:raw', $out_file or die "open $out_file: $!";
my @cases = grep { length } @{ read_lines("$dir/cases.tsv") };
my $n = scalar @cases;
my $i = 0;
my $limit = $ENV{BENCH_LIMIT};
for my $line (@cases) {
    last if $limit && $i >= $limit;
    $i++;
    my ($id, $expect, $text) = split /\t/, $line, 3;
    my $j = jev_ask($text);
    sleep 0.15;
    my $l = llm_ask($text);
    sleep 0.15;
    print $out sprintf(
        '{"id":%d,"expect":"%s","jev":{"ms":%d,"tls":%d%s%s},"llm":{"ms":%d,"tls":%d%s%s}}' . "\n",
        $id, $expect,
        $j->{ms}, $j->{tls} // -1,
        defined $j->{noul} ? ',"noul":' . $j->{noul} : '',
        $j->{dec} ? ',"dec":"' . $j->{dec} . '"'
                  : ',"err":"' . esc($j->{err} // '?') . '"',
        $l->{ms}, $l->{tls} // -1,
        ($l->{out} // '') ne '' ? ',"out":"' . esc($l->{out}) . '"' : '',
        $l->{dec} ? ',"dec":"' . $l->{dec} . '"'
                  : ',"err":"' . esc($l->{err} // '?') . '"');
    $out->flush;
    printf STDERR "[%03d/%03d] id=%s exp=%s jev=%s(%sms) llm=%s(%sms)\n",
        $i, $n, $id, $expect,
        $j->{dec} // 'ERR', $j->{ms},
        $l->{dec} // 'ERR', $l->{ms};
}
close $out;
print "BENCH_DONE $out_file lines=$i\n";
